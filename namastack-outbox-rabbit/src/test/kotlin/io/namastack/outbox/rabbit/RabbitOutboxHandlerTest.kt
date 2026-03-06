package io.namastack.outbox.rabbit

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.routing.selector.OutboxPayloadSelector
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.amqp.rabbit.core.RabbitMessageOperations
import java.time.Instant
import java.util.concurrent.ExecutionException

@DisplayName("RabbitOutboxHandler")
class RabbitOutboxHandlerTest {
    private lateinit var rabbitMessageOperations: RabbitMessageOperations
    private lateinit var handler: RabbitOutboxHandler

    private val metadata =
        OutboxRecordMetadata(
            key = "order-123",
            handlerId = "test-handler",
            createdAt = Instant.now(),
            context = mapOf("tenant" to "acme"),
        )

    @BeforeEach
    fun setUp() {
        rabbitMessageOperations = mockk(relaxed = true)
    }

    @Nested
    @DisplayName("handle()")
    inner class Handle {
        @Test
        fun `sends payload to resolved exchange`() {
            val routing =
                rabbitOutboxRouting {
                    defaults { target("default-exchange") }
                }
            handler = RabbitOutboxHandler(rabbitMessageOperations, routing)

            handler.handle("test-payload", metadata)

            verify(exactly = 1) {
                rabbitMessageOperations.convertAndSend(
                    "default-exchange",
                    metadata.key,
                    "test-payload",
                    any<Map<String, String>>(),
                )
            }
        }

        @Test
        fun `sends payload with correct routing key`() {
            val routing =
                rabbitOutboxRouting {
                    defaults {
                        target("events")
                        key { _, meta -> meta.key }
                    }
                }
            handler = RabbitOutboxHandler(rabbitMessageOperations, routing)

            handler.handle("test-payload", metadata)

            verify(exactly = 1) {
                rabbitMessageOperations.convertAndSend(
                    "events",
                    "order-123",
                    "test-payload",
                    any<Map<String, String>>(),
                )
            }
        }

        @Test
        fun `sends payload with headers`() {
            val routing =
                rabbitOutboxRouting {
                    defaults {
                        target("events")
                        headers { _, meta -> meta.context }
                    }
                }
            handler = RabbitOutboxHandler(rabbitMessageOperations, routing)

            val headersSlot = slot<Map<String, String>>()

            every {
                rabbitMessageOperations.convertAndSend(
                    "events",
                    any(),
                    any(),
                    capture(headersSlot),
                )
            } returns Unit

            handler.handle("test-payload", metadata)

            assertThat(headersSlot.captured).containsEntry("tenant", "acme")
        }

        @Test
        fun `applies payload mapping before sending`() {
            val routing =
                rabbitOutboxRouting {
                    defaults {
                        target("events")
                        mapping { payload, _ -> (payload as String).uppercase() }
                    }
                }
            handler = RabbitOutboxHandler(rabbitMessageOperations, routing)

            handler.handle("test-payload", metadata)

            verify(exactly = 1) {
                rabbitMessageOperations.convertAndSend(
                    "events",
                    any(),
                    "TEST-PAYLOAD",
                    any<Map<String, String>>(),
                )
            }
        }

        @Test
        fun `skips sending when filter returns false`() {
            val routing =
                rabbitOutboxRouting {
                    defaults {
                        target("events")
                        filter { payload, _ -> (payload as String) != "skip-me" }
                    }
                }
            handler = RabbitOutboxHandler(rabbitMessageOperations, routing)

            handler.handle("skip-me", metadata)

            verify(exactly = 0) {
                rabbitMessageOperations.convertAndSend(any<String>(), any(), any(), any<Map<String, String>>())
            }
        }

        @Test
        fun `sends when filter returns true`() {
            val routing =
                rabbitOutboxRouting {
                    defaults {
                        target("events")
                        filter { payload, _ -> (payload as String) != "skip-me" }
                    }
                }
            handler = RabbitOutboxHandler(rabbitMessageOperations, routing)

            handler.handle("send-me", metadata)

            verify(exactly = 1) {
                rabbitMessageOperations.convertAndSend(
                    "events",
                    any(),
                    "send-me",
                    any<Map<String, String>>(),
                )
            }
        }

        @Test
        fun `routes to correct exchange based on payload type`() {
            data class OrderEvent(
                val orderId: String,
            )

            data class PaymentEvent(
                val paymentId: String,
            )

            val routing =
                rabbitOutboxRouting {
                    route(OutboxPayloadSelector.type(OrderEvent::class.java)) {
                        target("orders")
                    }
                    route(OutboxPayloadSelector.type(PaymentEvent::class.java)) {
                        target("payments")
                    }
                    defaults { target("events") }
                }
            handler = RabbitOutboxHandler(rabbitMessageOperations, routing)

            handler.handle(OrderEvent("order-1"), metadata)

            verify(exactly = 1) {
                rabbitMessageOperations.convertAndSend(
                    "orders",
                    any(),
                    any(),
                    any<Map<String, String>>(),
                )
            }
        }

        @Test
        fun `uses dynamic exchange resolver`() {
            data class Event(
                val type: String,
            )

            val routing =
                rabbitOutboxRouting {
                    defaults {
                        target { payload, _ -> "events-${(payload as Event).type}" }
                    }
                }
            handler = RabbitOutboxHandler(rabbitMessageOperations, routing)

            handler.handle(Event("created"), metadata)

            verify(exactly = 1) {
                rabbitMessageOperations.convertAndSend(
                    "events-created",
                    any(),
                    any(),
                    any<Map<String, String>>(),
                )
            }
        }
    }

    @Nested
    @DisplayName("error handling")
    inner class ErrorHandling {
        @Test
        fun `throws cause when ExecutionException occurs`() {
            val routing =
                rabbitOutboxRouting {
                    defaults { target("events") }
                }
            handler = RabbitOutboxHandler(rabbitMessageOperations, routing)

            val cause = RuntimeException("Rabbit unavailable")
            every {
                rabbitMessageOperations.convertAndSend(any<String>(), any(), any(), any<Map<String, String>>())
            } throws ExecutionException(cause)

            assertThatThrownBy { handler.handle("payload", metadata) }
                .isInstanceOf(RuntimeException::class.java)
                .hasMessage("Rabbit unavailable")
        }

        @Test
        fun `throws ExecutionException when cause is null`() {
            val routing =
                rabbitOutboxRouting {
                    defaults { target("events") }
                }
            handler = RabbitOutboxHandler(rabbitMessageOperations, routing)

            every {
                rabbitMessageOperations.convertAndSend(any<String>(), any(), any(), any<Map<String, String>>())
            } throws ExecutionException(null)

            assertThatThrownBy { handler.handle("payload", metadata) }
                .isInstanceOf(ExecutionException::class.java)
        }

        @Test
        fun `restores interrupt flag when InterruptedException occurs`() {
            val routing =
                rabbitOutboxRouting {
                    defaults { target("events") }
                }
            handler = RabbitOutboxHandler(rabbitMessageOperations, routing)

            every {
                rabbitMessageOperations.convertAndSend(any<String>(), any(), any(), any<Map<String, String>>())
            } answers {
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
                rabbitOutboxRouting {
                    route(OutboxPayloadSelector.type(OrderEvent::class.java)) {
                        target("orders")
                        key { event, _ -> (event as OrderEvent).orderId }
                        headers { _, meta -> mapOf("tenant" to (meta.context["tenant"] ?: "unknown")) }
                        mapping { event, _ -> PublicOrderEvent((event as OrderEvent).orderId) }
                        filter { event, _ -> (event as OrderEvent).status != "CANCELLED" }
                    }
                }
            handler = RabbitOutboxHandler(rabbitMessageOperations, routing)

            val headersSlot = slot<Map<String, String>>()
            val routingKeySlot = slot<String>()
            val payloadSlot = slot<Any>()

            every {
                rabbitMessageOperations.convertAndSend(
                    "orders",
                    capture(routingKeySlot),
                    capture(payloadSlot),
                    capture(headersSlot),
                )
            } returns Unit

            handler.handle(OrderEvent("order-456", "CREATED"), metadata)

            assertThat(routingKeySlot.captured).isEqualTo("order-456")
            assertThat(payloadSlot.captured).isEqualTo(PublicOrderEvent("order-456"))
            assertThat(headersSlot.captured).containsEntry("tenant", "acme")
        }

        @Test
        fun `filters out cancelled orders`() {
            data class OrderEvent(
                val orderId: String,
                val status: String,
            )

            val routing =
                rabbitOutboxRouting {
                    route(OutboxPayloadSelector.type(OrderEvent::class.java)) {
                        target("orders")
                        filter { event, _ -> (event as OrderEvent).status != "CANCELLED" }
                    }
                }
            handler = RabbitOutboxHandler(rabbitMessageOperations, routing)

            handler.handle(OrderEvent("order-789", "CANCELLED"), metadata)

            verify(exactly = 0) {
                rabbitMessageOperations.convertAndSend(any<String>(), any(), any(), any<Map<String, String>>())
            }
        }
    }
}
