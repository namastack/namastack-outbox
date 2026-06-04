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
import org.springframework.amqp.AmqpException
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessagePostProcessor
import org.springframework.amqp.core.MessageProperties
import org.springframework.amqp.core.ReturnedMessage
import org.springframework.amqp.rabbit.core.RabbitTemplate
import java.time.Instant

@DisplayName("RabbitOutboxHandler")
class RabbitOutboxHandlerTest {
    private lateinit var rabbitTemplate: RabbitTemplate
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
        rabbitTemplate = mockk(relaxed = true)
    }

    @Nested
    @DisplayName("initialization")
    inner class Initialization {
        @Test
        fun `configures mandatory publishing and returns callback`() {
            val routing =
                rabbitOutboxRouting {
                    defaults { target("events") }
                }
            val returnsCallbackSlot = slot<RabbitTemplate.ReturnsCallback>()

            every { rabbitTemplate.setReturnsCallback(capture(returnsCallbackSlot)) } returns Unit

            handler = RabbitOutboxHandler(rabbitTemplate, routing)

            verify(exactly = 1) { rabbitTemplate.setMandatory(true) }
            verify(exactly = 1) { rabbitTemplate.setReturnsCallback(any()) }

            val returned =
                ReturnedMessage(
                    Message(ByteArray(0), MessageProperties()),
                    312,
                    "NO_ROUTE",
                    "events",
                    "order-123",
                )

            assertThatThrownBy { returnsCallbackSlot.captured.returnedMessage(returned) }
                .isInstanceOf(AmqpException::class.java)
                .hasMessageContaining("Unroutable message")
                .hasMessageContaining("exchange=events")
                .hasMessageContaining("routingKey=order-123")
        }
    }

    @Nested
    @DisplayName("handle()")
    inner class Handle {
        @Test
        fun `supports returns false when filter excludes payload`() {
            val routing =
                rabbitOutboxRouting {
                    defaults {
                        target("events")
                        filter { payload, _ -> (payload as String) != "skip-me" }
                    }
                }
            handler = RabbitOutboxHandler(rabbitTemplate, routing)

            assertThat(handler.supports("skip-me", metadata)).isFalse()
        }

        @Test
        fun `sends payload to resolved exchange`() {
            val routing =
                rabbitOutboxRouting {
                    defaults { target("default-exchange") }
                }
            handler = RabbitOutboxHandler(rabbitTemplate, routing)

            handler.handle("test-payload", metadata)

            verify(exactly = 1) {
                rabbitTemplate.convertAndSend(
                    "default-exchange",
                    metadata.key,
                    "test-payload",
                    any<MessagePostProcessor>(),
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
            handler = RabbitOutboxHandler(rabbitTemplate, routing)

            handler.handle("test-payload", metadata)

            verify(exactly = 1) {
                rabbitTemplate.convertAndSend(
                    "events",
                    "order-123",
                    "test-payload",
                    any<MessagePostProcessor>(),
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
            handler = RabbitOutboxHandler(rabbitTemplate, routing)

            val postProcessorSlot = slot<MessagePostProcessor>()

            every {
                rabbitTemplate.convertAndSend(
                    "events",
                    any<String>(),
                    any(),
                    capture(postProcessorSlot),
                )
            } returns Unit

            handler.handle("test-payload", metadata)

            val message = Message(ByteArray(0), MessageProperties())
            postProcessorSlot.captured.postProcessMessage(message)

            assertThat(message.messageProperties.headers).containsEntry("tenant", "acme")
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
            handler = RabbitOutboxHandler(rabbitTemplate, routing)

            handler.handle("test-payload", metadata)

            verify(exactly = 1) {
                rabbitTemplate.convertAndSend(
                    "events",
                    any<String>(),
                    "TEST-PAYLOAD",
                    any<MessagePostProcessor>(),
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
            handler = RabbitOutboxHandler(rabbitTemplate, routing)

            handler.handle("skip-me", metadata)

            verify(exactly = 0) {
                rabbitTemplate.convertAndSend(any<String>(), any<String>(), any(), any<MessagePostProcessor>())
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
            handler = RabbitOutboxHandler(rabbitTemplate, routing)

            handler.handle("send-me", metadata)

            verify(exactly = 1) {
                rabbitTemplate.convertAndSend(
                    "events",
                    any<String>(),
                    "send-me",
                    any<MessagePostProcessor>(),
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
            handler = RabbitOutboxHandler(rabbitTemplate, routing)

            handler.handle(OrderEvent("order-1"), metadata)

            verify(exactly = 1) {
                rabbitTemplate.convertAndSend(
                    "orders",
                    any<String>(),
                    any(),
                    any<MessagePostProcessor>(),
                )
            }
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

            val routing =
                rabbitOutboxRouting {
                    route(OutboxPayloadSelector.type(OrderEvent::class.java)) {
                        target("orders")
                    }
                    route(OutboxPayloadSelector.type(PaymentEvent::class.java)) {
                        target("payments")
                    }
                }
            handler = RabbitOutboxHandler(rabbitTemplate, routing)

            handler.handle(UserEvent("user-1"), metadata)

            verify(exactly = 0) {
                rabbitTemplate.convertAndSend(any<String>(), any<String>(), any(), any<MessagePostProcessor>())
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
            handler = RabbitOutboxHandler(rabbitTemplate, routing)

            handler.handle(Event("created"), metadata)

            verify(exactly = 1) {
                rabbitTemplate.convertAndSend(
                    "events-created",
                    any<String>(),
                    any(),
                    any<MessagePostProcessor>(),
                )
            }
        }
    }

    @Nested
    @DisplayName("error handling")
    inner class ErrorHandling {
        @Test
        fun `throws AmqpException from RabbitTemplate send`() {
            val routing =
                rabbitOutboxRouting {
                    defaults { target("events") }
                }

            every {
                rabbitTemplate.convertAndSend(
                    any<String>(),
                    any<String>(),
                    any(),
                    any<MessagePostProcessor>(),
                )
            } throws AmqpException("Rabbit unavailable")

            handler = RabbitOutboxHandler(rabbitTemplate, routing)

            assertThatThrownBy { handler.handle("payload", metadata) }
                .isInstanceOf(AmqpException::class.java)
                .hasMessage("Rabbit unavailable")
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
            handler = RabbitOutboxHandler(rabbitTemplate, routing)

            val postProcessorSlot = slot<MessagePostProcessor>()
            val routingKeySlot = slot<String>()
            val payloadSlot = slot<Any>()

            every {
                rabbitTemplate.convertAndSend(
                    "orders",
                    capture(routingKeySlot),
                    capture(payloadSlot),
                    capture(postProcessorSlot),
                )
            } returns Unit

            handler.handle(OrderEvent("order-456", "CREATED"), metadata)

            val message = Message(ByteArray(0), MessageProperties())
            postProcessorSlot.captured.postProcessMessage(message)

            assertThat(routingKeySlot.captured).isEqualTo("order-456")
            assertThat(payloadSlot.captured).isEqualTo(PublicOrderEvent("order-456"))
            assertThat(message.messageProperties.headers).containsEntry("tenant", "acme")
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
            handler = RabbitOutboxHandler(rabbitTemplate, routing)

            handler.handle(OrderEvent("order-789", "CANCELLED"), metadata)

            verify(exactly = 0) {
                rabbitTemplate.convertAndSend(any<String>(), any<String>(), any(), any<MessagePostProcessor>())
            }
        }
    }
}
