package io.namastack.outbox.sns

import io.awspring.cloud.sns.core.SnsNotification
import io.awspring.cloud.sns.core.SnsOperations
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
import java.time.Instant
import java.util.concurrent.ExecutionException

@DisplayName("SnsOutboxHandler")
class SnsOutboxHandlerTest {
    private lateinit var snsOperations: SnsOperations
    private lateinit var handler: SnsOutboxHandler

    private val metadata =
        OutboxRecordMetadata(
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
    @DisplayName("handle()")
    inner class Handle {
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
