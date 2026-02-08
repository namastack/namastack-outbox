package io.namastack.outbox.kafka

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.routing.selector.OutboxPayloadSelector
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaOperations
import org.springframework.kafka.support.SendResult
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

@DisplayName("KafkaOutboxHandler")
class KafkaOutboxHandlerTest {
    private lateinit var kafkaOperations: KafkaOperations<String, Any>
    private lateinit var handler: KafkaOutboxHandler

    private val metadata =
        OutboxRecordMetadata(
            key = "order-123",
            handlerId = "test-handler",
            createdAt = Instant.now(),
            context = mapOf("tenant" to "acme"),
        )

    @BeforeEach
    fun setUp() {
        kafkaOperations = mockk()
    }

    @Nested
    @DisplayName("handle()")
    inner class Handle {
        @Test
        fun `sends payload to resolved topic`() {
            val routing =
                kafkaRouting {
                    defaults { topic("default-topic") }
                }
            handler = KafkaOutboxHandler(kafkaOperations, routing)

            val recordSlot = slot<ProducerRecord<String, Any>>()
            every { kafkaOperations.send(capture(recordSlot)) } returns successFuture("default-topic")

            handler.handle("test-payload", metadata)

            assertThat(recordSlot.captured.topic()).isEqualTo("default-topic")
            assertThat(recordSlot.captured.value()).isEqualTo("test-payload")
        }

        @Test
        fun `sends payload with correct key`() {
            val routing =
                kafkaRouting {
                    defaults {
                        topic("events")
                        key { _, meta -> meta.key }
                    }
                }
            handler = KafkaOutboxHandler(kafkaOperations, routing)

            val recordSlot = slot<ProducerRecord<String, Any>>()
            every { kafkaOperations.send(capture(recordSlot)) } returns successFuture("events")

            handler.handle("test-payload", metadata)

            assertThat(recordSlot.captured.key()).isEqualTo("order-123")
        }

        @Test
        fun `sends payload with headers`() {
            val routing =
                kafkaRouting {
                    defaults {
                        topic("events")
                        headers { _, meta -> meta.context }
                    }
                }
            handler = KafkaOutboxHandler(kafkaOperations, routing)

            val recordSlot = slot<ProducerRecord<String, Any>>()
            every { kafkaOperations.send(capture(recordSlot)) } returns successFuture("events")

            handler.handle("test-payload", metadata)

            val headers = recordSlot.captured.headers()
            assertThat(headers.lastHeader("tenant").value()).isEqualTo("acme".toByteArray())
        }

        @Test
        fun `applies payload mapping before sending`() {
            val routing =
                kafkaRouting {
                    defaults {
                        topic("events")
                        mapping { payload, _ -> (payload as String).uppercase() }
                    }
                }
            handler = KafkaOutboxHandler(kafkaOperations, routing)

            val recordSlot = slot<ProducerRecord<String, Any>>()
            every { kafkaOperations.send(capture(recordSlot)) } returns successFuture("events")

            handler.handle("test-payload", metadata)

            assertThat(recordSlot.captured.value()).isEqualTo("TEST-PAYLOAD")
        }

        @Test
        fun `skips sending when filter returns false`() {
            val routing =
                kafkaRouting {
                    defaults {
                        topic("events")
                        filter { payload, _ -> (payload as String) != "skip-me" }
                    }
                }
            handler = KafkaOutboxHandler(kafkaOperations, routing)

            handler.handle("skip-me", metadata)

            verify(exactly = 0) { kafkaOperations.send(any<ProducerRecord<String, Any>>()) }
        }

        @Test
        fun `sends when filter returns true`() {
            val routing =
                kafkaRouting {
                    defaults {
                        topic("events")
                        filter { payload, _ -> (payload as String) != "skip-me" }
                    }
                }
            handler = KafkaOutboxHandler(kafkaOperations, routing)

            every { kafkaOperations.send(any<ProducerRecord<String, Any>>()) } returns successFuture("events")

            handler.handle("send-me", metadata)

            verify(exactly = 1) { kafkaOperations.send(any<ProducerRecord<String, Any>>()) }
        }

        @Test
        fun `routes to correct topic based on payload type`() {
            data class OrderEvent(
                val orderId: String,
            )

            data class PaymentEvent(
                val paymentId: String,
            )

            val routing =
                kafkaRouting {
                    route(OutboxPayloadSelector.type(OrderEvent::class.java)) {
                        topic("orders")
                    }
                    route(OutboxPayloadSelector.type(PaymentEvent::class.java)) {
                        topic("payments")
                    }
                    defaults { topic("events") }
                }
            handler = KafkaOutboxHandler(kafkaOperations, routing)

            val recordSlot = slot<ProducerRecord<String, Any>>()
            every { kafkaOperations.send(capture(recordSlot)) } returns successFuture("orders")

            handler.handle(OrderEvent("order-1"), metadata)

            assertThat(recordSlot.captured.topic()).isEqualTo("orders")
        }

        @Test
        fun `uses dynamic topic resolver`() {
            data class Event(
                val type: String,
            )

            val routing =
                kafkaRouting {
                    defaults {
                        topic { payload, _ -> "events-${(payload as Event).type}" }
                    }
                }
            handler = KafkaOutboxHandler(kafkaOperations, routing)

            val recordSlot = slot<ProducerRecord<String, Any>>()
            every { kafkaOperations.send(capture(recordSlot)) } returns successFuture("events-created")

            handler.handle(Event("created"), metadata)

            assertThat(recordSlot.captured.topic()).isEqualTo("events-created")
        }
    }

    @Nested
    @DisplayName("error handling")
    inner class ErrorHandling {
        @Test
        fun `throws cause when ExecutionException occurs`() {
            val routing =
                kafkaRouting {
                    defaults { topic("events") }
                }
            handler = KafkaOutboxHandler(kafkaOperations, routing)

            val cause = RuntimeException("Kafka unavailable")
            val future = CompletableFuture<SendResult<String, Any>>()
            future.completeExceptionally(cause)

            every { kafkaOperations.send(any<ProducerRecord<String, Any>>()) } returns future

            assertThatThrownBy { handler.handle("payload", metadata) }
                .isInstanceOf(RuntimeException::class.java)
                .hasMessage("Kafka unavailable")
        }

        @Test
        fun `throws ExecutionException when cause is null`() {
            val routing =
                kafkaRouting {
                    defaults { topic("events") }
                }
            handler = KafkaOutboxHandler(kafkaOperations, routing)

            val future = CompletableFuture<SendResult<String, Any>>()
            future.completeExceptionally(ExecutionException(null))

            every { kafkaOperations.send(any<ProducerRecord<String, Any>>()) } returns future

            assertThatThrownBy { handler.handle("payload", metadata) }
                .isInstanceOf(ExecutionException::class.java)
        }

        @Test
        fun `restores interrupt flag when InterruptedException occurs`() {
            val routing =
                kafkaRouting {
                    defaults { topic("events") }
                }
            handler = KafkaOutboxHandler(kafkaOperations, routing)

            every { kafkaOperations.send(any<ProducerRecord<String, Any>>()) } answers {
                Thread.currentThread().interrupt()
                throw InterruptedException("Interrupted")
            }

            assertThatThrownBy { handler.handle("payload", metadata) }
                .isInstanceOf(InterruptedException::class.java)

            assertThat(Thread.currentThread().isInterrupted).isTrue()

            // Clear the interrupt flag for other tests
            Thread.interrupted()
        }
    }

    @Nested
    @DisplayName("full configuration")
    inner class FullConfiguration {
        @Test
        fun `applies all routing options correctly`() {
            data class OrderEvent(
                val orderId: String,
                val status: String,
            )

            data class PublicOrderEvent(
                val id: String,
            )

            val routing =
                kafkaRouting {
                    route(OutboxPayloadSelector.type(OrderEvent::class.java)) {
                        topic("orders")
                        key { event, _ -> (event as OrderEvent).orderId }
                        headers { _, meta -> mapOf("tenant" to (meta.context["tenant"] ?: "unknown")) }
                        mapping { event, _ -> PublicOrderEvent((event as OrderEvent).orderId) }
                        filter { event, _ -> (event as OrderEvent).status != "CANCELLED" }
                    }
                }
            handler = KafkaOutboxHandler(kafkaOperations, routing)

            val recordSlot = slot<ProducerRecord<String, Any>>()
            every { kafkaOperations.send(capture(recordSlot)) } returns successFuture("orders")

            handler.handle(OrderEvent("order-456", "CREATED"), metadata)

            val record = recordSlot.captured
            assertThat(record.topic()).isEqualTo("orders")
            assertThat(record.key()).isEqualTo("order-456")
            assertThat(record.value()).isEqualTo(PublicOrderEvent("order-456"))
            assertThat(record.headers().lastHeader("tenant").value()).isEqualTo("acme".toByteArray())
        }

        @Test
        fun `filters out cancelled orders`() {
            data class OrderEvent(
                val orderId: String,
                val status: String,
            )

            val routing =
                kafkaRouting {
                    route(OutboxPayloadSelector.type(OrderEvent::class.java)) {
                        topic("orders")
                        filter { event, _ -> (event as OrderEvent).status != "CANCELLED" }
                    }
                }
            handler = KafkaOutboxHandler(kafkaOperations, routing)

            handler.handle(OrderEvent("order-789", "CANCELLED"), metadata)

            verify(exactly = 0) { kafkaOperations.send(any<ProducerRecord<String, Any>>()) }
        }
    }

    private fun successFuture(topic: String): CompletableFuture<SendResult<String, Any>> {
        val recordMetadata = RecordMetadata(TopicPartition(topic, 0), 0, 0, 0, 0, 0)
        val sendResult = mockk<SendResult<String, Any>>()
        every { sendResult.recordMetadata } returns recordMetadata

        return CompletableFuture.completedFuture(sendResult)
    }
}
