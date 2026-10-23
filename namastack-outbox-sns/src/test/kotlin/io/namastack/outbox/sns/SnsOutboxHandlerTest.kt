package io.namastack.outbox.sns

import io.awspring.cloud.sns.core.SnsHeaders
import io.awspring.cloud.sns.core.SnsNotification
import io.awspring.cloud.sns.core.SnsOperations
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.routing.OutboxHeaders
import io.namastack.outbox.routing.selector.OutboxPayloadSelector
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.util.concurrent.ExecutionException

@DisplayName("SnsOutboxHandler")
class SnsOutboxHandlerTest {
    private lateinit var snsOperations: SnsOperations
    private lateinit var handler: SnsOutboxHandler

    private val metadata =
        OutboxRecordMetadata(
            recordId = "record-id",
            key = "order-123",
            handlerId = "test-handler",
            createdAt = Instant.now(),
            context = mapOf("tenant" to "acme"),
        )

    @BeforeEach
    fun setUp() {
        snsOperations = mockk(relaxed = true)
    }

    @Nested
    @DisplayName("Idempotency")
    inner class Idempotency {
        private val fifoTopic = "arn:aws:sns:us-east-1:123456789012:events.fifo"
        private val standardTopic = "arn:aws:sns:us-east-1:123456789012:events"

        @Test
        fun `rejects missing record ID for native FIFO fallback before sending`() {
            handler = SnsOutboxHandler(snsOperations, routing(fifoTopic), fifoDeduplicationIdEnabled = true)

            assertThatThrownBy { handler.handle("payload", metadata.copy(recordId = null)) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("recordId")
            verify(exactly = 0) { snsOperations.sendNotification(any<String>(), any<SnsNotification<*>>()) }
        }

        @ParameterizedTest
        @CsvSource(
            "false, false, false",
            "false, false, true",
            "true, false, false",
            "true, false, true",
            "false, true, false",
            "false, true, true",
            "true, true, false",
            "true, true, true",
        )
        fun `controls attributes and native FIFO deduplication independently`(
            idempotencyHeaderEnabled: Boolean,
            fifoDeduplicationIdEnabled: Boolean,
            fifo: Boolean,
        ) {
            val topic = if (fifo) fifoTopic else standardTopic
            handler =
                SnsOutboxHandler(
                    snsOperations,
                    routing(topic),
                    idempotencyHeaderEnabled,
                    fifoDeduplicationIdEnabled,
                )
            val notifications = mutableListOf<SnsNotification<*>>()
            every { snsOperations.sendNotification(topic, capture(notifications)) } returns Unit

            handler.handle("payload", metadata)
            handler.handle("payload", metadata.copy(failureCount = 1))

            assertThat(notifications).hasSize(2)
            notifications.forEach { notification ->
                assertThat(notification.headers?.get(OutboxHeaders.IDEMPOTENCY_KEY))
                    .isEqualTo(if (idempotencyHeaderEnabled) "record-id" else null)
                assertThat(notification.deduplicationId)
                    .isEqualTo(if (fifoDeduplicationIdEnabled && fifo) "record-id" else null)
                assertThat(notification.groupId).isEqualTo("order-123")
            }
        }

        @Test
        fun `uses explicit idempotency header for native deduplication without automatic attributes`() {
            handler =
                SnsOutboxHandler(
                    snsOperations,
                    routing(fifoTopic, mapOf(OutboxHeaders.IDEMPOTENCY_KEY to "event-id")),
                    fifoDeduplicationIdEnabled = true,
                )
            val notificationSlot = slot<SnsNotification<*>>()
            every { snsOperations.sendNotification(fifoTopic, capture(notificationSlot)) } returns Unit

            handler.handle("payload", metadata.copy(recordId = null))

            assertThat(notificationSlot.captured.deduplicationId).isEqualTo("event-id")
            assertThat(notificationSlot.captured.headers).containsEntry(OutboxHeaders.IDEMPOTENCY_KEY, "event-id")
        }

        @Test
        fun `native header takes precedence without replacing the custom idempotency attribute`() {
            handler =
                SnsOutboxHandler(
                    snsOperations,
                    routing(
                        fifoTopic,
                        mapOf(
                            SnsHeaders.MESSAGE_DEDUPLICATION_ID_HEADER to "native-id",
                            OutboxHeaders.IDEMPOTENCY_KEY to "event-id",
                            "tenant" to "acme",
                        ),
                    ),
                    idempotencyHeaderEnabled = true,
                    fifoDeduplicationIdEnabled = true,
                )
            val notificationSlot = slot<SnsNotification<*>>()
            every { snsOperations.sendNotification(fifoTopic, capture(notificationSlot)) } returns Unit

            handler.handle("payload", metadata)

            assertThat(notificationSlot.captured.deduplicationId).isEqualTo("native-id")
            assertThat(notificationSlot.captured.headers)
                .containsEntry(OutboxHeaders.IDEMPOTENCY_KEY, "event-id")
                .containsEntry("tenant", "acme")
        }

        @ParameterizedTest
        @ValueSource(strings = ["", " ", "\t\n"])
        fun `rejects blank idempotency attributes before sending`(key: String) {
            handler =
                SnsOutboxHandler(
                    snsOperations,
                    routing(standardTopic, mapOf(OutboxHeaders.IDEMPOTENCY_KEY to key)),
                    idempotencyHeaderEnabled = true,
                )

            assertThatThrownBy { handler.handle("payload", metadata) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining(OutboxHeaders.IDEMPOTENCY_KEY)
            verify(exactly = 0) { snsOperations.sendNotification(any<String>(), any<SnsNotification<*>>()) }
        }

        @ParameterizedTest
        @ValueSource(strings = ["", " ", "\t\n"])
        fun `native FIFO deduplication rejects both blank explicit keys`(key: String) {
            listOf(SnsHeaders.MESSAGE_DEDUPLICATION_ID_HEADER, OutboxHeaders.IDEMPOTENCY_KEY).forEach { header ->
                val headers =
                    mapOf(
                        SnsHeaders.MESSAGE_DEDUPLICATION_ID_HEADER to "native-id",
                        OutboxHeaders.IDEMPOTENCY_KEY to "event-id",
                    ) + (header to key)
                handler =
                    SnsOutboxHandler(snsOperations, routing(fifoTopic, headers), fifoDeduplicationIdEnabled = true)

                assertThatThrownBy { handler.handle("payload", metadata) }
                    .isInstanceOf(IllegalArgumentException::class.java)
                    .hasMessageContaining(header)
            }
            verify(exactly = 0) { snsOperations.sendNotification(any<String>(), any<SnsNotification<*>>()) }
        }

        @Test
        fun `disabled settings preserve existing blank and native headers`() {
            val headers =
                mapOf(
                    OutboxHeaders.IDEMPOTENCY_KEY to "",
                    SnsHeaders.MESSAGE_DEDUPLICATION_ID_HEADER to "existing-native-id",
                )
            handler = SnsOutboxHandler(snsOperations, routing(fifoTopic, headers))
            val notificationSlot = slot<SnsNotification<*>>()
            every { snsOperations.sendNotification(fifoTopic, capture(notificationSlot)) } returns Unit

            handler.handle("payload", metadata)

            assertThat(notificationSlot.captured.headers).containsAllEntriesOf(headers)
            assertThat(notificationSlot.captured.deduplicationId).isEqualTo("existing-native-id")
        }

        @Test
        fun `native FIFO option does not validate or modify standard topic headers`() {
            val headers = mapOf(OutboxHeaders.IDEMPOTENCY_KEY to "")
            handler =
                SnsOutboxHandler(snsOperations, routing(standardTopic, headers), fifoDeduplicationIdEnabled = true)
            val notificationSlot = slot<SnsNotification<*>>()
            every { snsOperations.sendNotification(standardTopic, capture(notificationSlot)) } returns Unit

            handler.handle("payload", metadata)

            assertThat(notificationSlot.captured.headers).containsAllEntriesOf(headers)
            assertThat(notificationSlot.captured.deduplicationId).isNull()
        }

        private fun routing(
            topic: String,
            configuredHeaders: Map<String, String> = emptyMap(),
        ): SnsOutboxRouting =
            snsOutboxRouting {
                defaults {
                    target(topic)
                    headers { _, _ -> configuredHeaders }
                }
            }
    }

    @Nested
    @DisplayName("handle()")
    inner class Handle {
        @Test
        fun `supports returns false when filter excludes payload`() {
            val routing =
                snsOutboxRouting {
                    defaults {
                        target("arn:aws:sns:us-east-1:123456789012:events")
                        filter { payload, _ -> (payload as String) != "skip-me" }
                    }
                }
            handler = SnsOutboxHandler(snsOperations, routing)

            assertThat(handler.supports("skip-me", metadata)).isFalse()
        }

        @Test
        fun `sends payload to resolved topic ARN`() {
            val topicArn = "arn:aws:sns:us-east-1:123456789012:default-topic"
            val routing =
                snsOutboxRouting {
                    defaults { target(topicArn) }
                }
            handler = SnsOutboxHandler(snsOperations, routing)

            handler.handle("test-payload", metadata)

            verify(exactly = 1) {
                snsOperations.sendNotification(topicArn, any<SnsNotification<*>>())
            }
        }

        @Test
        fun `sends payload with correct message group id`() {
            val topicArn = "arn:aws:sns:us-east-1:123456789012:events"
            val routing =
                snsOutboxRouting {
                    defaults {
                        target(topicArn)
                        key { _, meta -> meta.key }
                    }
                }
            handler = SnsOutboxHandler(snsOperations, routing)

            val notificationSlot = slot<SnsNotification<*>>()
            every { snsOperations.sendNotification(topicArn, capture(notificationSlot)) } returns Unit

            handler.handle("test-payload", metadata)

            assertThat(notificationSlot.captured.groupId).isEqualTo("order-123")
        }

        @Test
        fun `sends payload with custom headers`() {
            val topicArn = "arn:aws:sns:us-east-1:123456789012:events"
            val routing =
                snsOutboxRouting {
                    defaults {
                        target(topicArn)
                        headers { _, meta -> meta.context }
                    }
                }
            handler = SnsOutboxHandler(snsOperations, routing)

            val notificationSlot = slot<SnsNotification<*>>()
            every { snsOperations.sendNotification(topicArn, capture(notificationSlot)) } returns Unit

            handler.handle("test-payload", metadata)

            assertThat(notificationSlot.captured.headers?.get("tenant")).isEqualTo("acme")
        }

        @Test
        fun `applies payload mapping before sending`() {
            val topicArn = "arn:aws:sns:us-east-1:123456789012:events"
            val routing =
                snsOutboxRouting {
                    defaults {
                        target(topicArn)
                        mapping { payload, _ -> (payload as String).uppercase() }
                    }
                }
            handler = SnsOutboxHandler(snsOperations, routing)

            val notificationSlot = slot<SnsNotification<*>>()
            every { snsOperations.sendNotification(topicArn, capture(notificationSlot)) } returns Unit

            handler.handle("test-payload", metadata)

            assertThat(notificationSlot.captured.payload).isEqualTo("TEST-PAYLOAD")
        }

        @Test
        fun `skips sending when filter returns false`() {
            val routing =
                snsOutboxRouting {
                    defaults {
                        target("arn:aws:sns:us-east-1:123456789012:events")
                        filter { payload, _ -> (payload as String) != "skip-me" }
                    }
                }
            handler = SnsOutboxHandler(snsOperations, routing)

            handler.handle("skip-me", metadata)

            verify(exactly = 0) { snsOperations.sendNotification(any<String>(), any<SnsNotification<*>>()) }
        }

        @Test
        fun `sends when filter returns true`() {
            val topicArn = "arn:aws:sns:us-east-1:123456789012:events"
            val routing =
                snsOutboxRouting {
                    defaults {
                        target(topicArn)
                        filter { payload, _ -> (payload as String) != "skip-me" }
                    }
                }
            handler = SnsOutboxHandler(snsOperations, routing)

            handler.handle("send-me", metadata)

            verify(exactly = 1) { snsOperations.sendNotification(topicArn, any<SnsNotification<*>>()) }
        }

        @Test
        fun `routes to correct topic ARN based on payload type`() {
            data class OrderEvent(
                val orderId: String,
            )

            data class PaymentEvent(
                val paymentId: String,
            )

            val ordersArn = "arn:aws:sns:us-east-1:123456789012:orders"
            val paymentsArn = "arn:aws:sns:us-east-1:123456789012:payments"

            val routing =
                snsOutboxRouting {
                    route(OutboxPayloadSelector.type(OrderEvent::class.java)) {
                        target(ordersArn)
                    }
                    route(OutboxPayloadSelector.type(PaymentEvent::class.java)) {
                        target(paymentsArn)
                    }
                    defaults { target("arn:aws:sns:us-east-1:123456789012:events") }
                }
            handler = SnsOutboxHandler(snsOperations, routing)

            handler.handle(OrderEvent("order-1"), metadata)

            verify(exactly = 1) { snsOperations.sendNotification(ordersArn, any<SnsNotification<*>>()) }
        }

        @Test
        fun `skips sending when routes do not match`() {
            data class OrderEvent(
                val orderId: String,
            )

            data class PaymentEvent(
                val paymentId: String,
            )

            data class UserEvent(
                val userId: String,
            )

            val ordersArn = "arn:aws:sns:us-east-1:123456789012:orders"
            val paymentsArn = "arn:aws:sns:us-east-1:123456789012:payments"

            val routing =
                snsOutboxRouting {
                    route(OutboxPayloadSelector.type(OrderEvent::class.java)) {
                        target(ordersArn)
                    }
                    route(OutboxPayloadSelector.type(PaymentEvent::class.java)) {
                        target(paymentsArn)
                    }
                }
            handler = SnsOutboxHandler(snsOperations, routing)

            handler.handle(UserEvent("user-1"), metadata)

            verify(exactly = 0) { snsOperations.sendNotification(any<String>(), any<SnsNotification<*>>()) }
        }

        @Test
        fun `uses dynamic topic ARN resolver`() {
            data class Event(
                val type: String,
            )

            val routing =
                snsOutboxRouting {
                    defaults {
                        target { payload, _ -> "arn:aws:sns:us-east-1:123456789012:events-${(payload as Event).type}" }
                    }
                }
            handler = SnsOutboxHandler(snsOperations, routing)

            handler.handle(Event("created"), metadata)

            verify(exactly = 1) {
                snsOperations.sendNotification(
                    "arn:aws:sns:us-east-1:123456789012:events-created",
                    any<SnsNotification<*>>(),
                )
            }
        }
    }

    @Nested
    @DisplayName("error handling")
    inner class ErrorHandling {
        @Test
        fun `throws cause when ExecutionException occurs`() {
            val topicArn = "arn:aws:sns:us-east-1:123456789012:events"
            val routing = snsOutboxRouting { defaults { target(topicArn) } }
            handler = SnsOutboxHandler(snsOperations, routing)

            val cause = RuntimeException("SNS unavailable")
            every {
                snsOperations.sendNotification(topicArn, any<SnsNotification<*>>())
            } throws ExecutionException(cause)

            assertThatThrownBy { handler.handle("payload", metadata) }
                .isInstanceOf(RuntimeException::class.java)
                .hasMessage("SNS unavailable")
        }

        @Test
        fun `throws ExecutionException when cause is null`() {
            val topicArn = "arn:aws:sns:us-east-1:123456789012:events"
            val routing = snsOutboxRouting { defaults { target(topicArn) } }
            handler = SnsOutboxHandler(snsOperations, routing)

            every {
                snsOperations.sendNotification(topicArn, any<SnsNotification<*>>())
            } throws ExecutionException(null)

            assertThatThrownBy { handler.handle("payload", metadata) }
                .isInstanceOf(ExecutionException::class.java)
        }

        @Test
        fun `restores interrupt flag when InterruptedException occurs`() {
            val topicArn = "arn:aws:sns:us-east-1:123456789012:events"
            val routing = snsOutboxRouting { defaults { target(topicArn) } }
            handler = SnsOutboxHandler(snsOperations, routing)

            every {
                snsOperations.sendNotification(topicArn, any<SnsNotification<*>>())
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

            val topicArn = "arn:aws:sns:us-east-1:123456789012:orders"
            val routing =
                snsOutboxRouting {
                    route(OutboxPayloadSelector.type(OrderEvent::class.java)) {
                        target(topicArn)
                        key { event, _ -> (event as OrderEvent).orderId }
                        headers { _, meta -> mapOf("tenant" to (meta.context["tenant"] ?: "unknown")) }
                        mapping { event, _ -> PublicOrderEvent((event as OrderEvent).orderId) }
                        filter { event, _ -> (event as OrderEvent).status != "CANCELLED" }
                    }
                }
            handler = SnsOutboxHandler(snsOperations, routing)

            val notificationSlot = slot<SnsNotification<*>>()
            every { snsOperations.sendNotification(topicArn, capture(notificationSlot)) } returns Unit

            handler.handle(OrderEvent("order-456", "CREATED"), metadata)

            assertThat(notificationSlot.captured.payload).isEqualTo(PublicOrderEvent("order-456"))
            assertThat(notificationSlot.captured.groupId).isEqualTo("order-456")
            assertThat(notificationSlot.captured.headers?.get("tenant")).isEqualTo("acme")
        }

        @Test
        fun `filters out cancelled orders`() {
            data class OrderEvent(
                val orderId: String,
                val status: String,
            )

            val topicArn = "arn:aws:sns:us-east-1:123456789012:orders"
            val routing =
                snsOutboxRouting {
                    route(OutboxPayloadSelector.type(OrderEvent::class.java)) {
                        target(topicArn)
                        filter { event, _ -> (event as OrderEvent).status != "CANCELLED" }
                    }
                }
            handler = SnsOutboxHandler(snsOperations, routing)

            handler.handle(OrderEvent("order-789", "CANCELLED"), metadata)

            verify(exactly = 0) { snsOperations.sendNotification(any<String>(), any<SnsNotification<*>>()) }
        }
    }
}
