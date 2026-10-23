package io.namastack.outbox.rabbit

import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.routing.OutboxHeaders
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant

@DisplayName("RabbitOutboxHandler")
class RabbitOutboxHandlerTest {
    private lateinit var publisher: RabbitOutboxPublisher
    private lateinit var handler: RabbitOutboxHandler

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
        publisher = mockk(relaxed = true)
    }

    @Nested
    @DisplayName("Idempotency")
    inner class Idempotency {
        @Test
        fun `publishes the same persisted ID on every attempt when enabled`() {
            val routing = rabbitOutboxRouting { defaults { target("events") } }
            handler = RabbitOutboxHandler(publisher, routing, idempotencyHeaderEnabled = true)
            val messages = mutableListOf<RabbitOutboxMessage>()

            handler.handle("payload", metadata)
            handler.handle("payload", metadata.copy(failureCount = 1))

            verify(exactly = 2) { publisher.publish(capture(messages)) }
            messages.forEach { message ->
                assertThat(message.headers).containsEntry(OutboxHeaders.IDEMPOTENCY_KEY, "record-id")
                assertThat(message.routingKey).isEqualTo("order-123")
            }
        }

        @Test
        fun `preserves custom idempotency key and other headers`() {
            val routing =
                rabbitOutboxRouting {
                    defaults {
                        target("events")
                        header(OutboxHeaders.IDEMPOTENCY_KEY) { _, _ -> "event-id" }
                        headers { _, meta -> meta.context }
                    }
                }
            handler = RabbitOutboxHandler(publisher, routing, idempotencyHeaderEnabled = true)
            val messageSlot = slot<RabbitOutboxMessage>()

            handler.handle("payload", metadata)

            verify { publisher.publish(capture(messageSlot)) }
            assertThat(messageSlot.captured.headers)
                .containsEntry(OutboxHeaders.IDEMPOTENCY_KEY, "event-id")
                .containsEntry("tenant", "acme")
        }

        @Test
        fun `does not add an idempotency header by default`() {
            handler = RabbitOutboxHandler(publisher, rabbitOutboxRouting { defaults { target("events") } })
            val messageSlot = slot<RabbitOutboxMessage>()

            handler.handle("payload", metadata)

            verify { publisher.publish(capture(messageSlot)) }
            assertThat(messageSlot.captured.headers).isEmpty()
        }

        @ParameterizedTest
        @ValueSource(strings = ["", " ", "\t\n"])
        fun `rejects blank keys before publishing`(key: String) {
            val routing =
                rabbitOutboxRouting {
                    defaults {
                        target("events")
                        header(OutboxHeaders.IDEMPOTENCY_KEY, key)
                    }
                }
            handler = RabbitOutboxHandler(publisher, routing, idempotencyHeaderEnabled = true)

            assertThatThrownBy { handler.handle("payload", metadata) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining(OutboxHeaders.IDEMPOTENCY_KEY)
            verify(exactly = 0) { publisher.publish(any()) }
        }
    }

    @Nested
    @DisplayName("handle()")
    inner class Handle {
        @Test
        fun `skips records rejected by routing filter`() {
            val routing =
                rabbitOutboxRouting {
                    defaults {
                        target("events")
                        filter { payload, _ -> (payload as String) != "skip-me" }
                    }
                }
            handler = RabbitOutboxHandler(publisher, routing)

            handler.handle("skip-me", metadata)

            assertThat(handler.supports("skip-me", metadata)).isFalse()
            verify(exactly = 0) { publisher.publish(any()) }
        }

        @Test
        fun `creates RabbitOutboxMessage correctly`() {
            val routing =
                rabbitOutboxRouting {
                    defaults {
                        target("events")
                        key { _, meta -> meta.key }
                        headers { _, meta -> meta.context }
                        mapping { payload, _ -> (payload as String).uppercase() }
                    }
                }
            val messageSlot = slot<RabbitOutboxMessage>()
            handler = RabbitOutboxHandler(publisher, routing)

            handler.handle("test-payload", metadata)

            verify(exactly = 1) { publisher.publish(capture(messageSlot)) }
            assertThat(messageSlot.captured).isEqualTo(
                RabbitOutboxMessage(
                    payload = "TEST-PAYLOAD",
                    exchange = "events",
                    routingKey = "order-123",
                    headers = mapOf("tenant" to "acme"),
                    handlerId = "test-handler",
                ),
            )
        }

        @Test
        fun `normalizes null routing key to empty string`() {
            val routing =
                rabbitOutboxRouting {
                    defaults {
                        target("events")
                        key { _, _ -> null }
                    }
                }
            val messageSlot = slot<RabbitOutboxMessage>()
            handler = RabbitOutboxHandler(publisher, routing)

            handler.handle("test-payload", metadata)

            verify(exactly = 1) { publisher.publish(capture(messageSlot)) }
            assertThat(messageSlot.captured.routingKey).isEmpty()
        }

        @Test
        fun `delegates publishing to RabbitOutboxPublisher`() {
            val routing =
                rabbitOutboxRouting {
                    defaults { target("events") }
                }
            handler = RabbitOutboxHandler(publisher, routing)

            handler.handle("test-payload", metadata)

            verify(exactly = 1) { publisher.publish(any()) }
        }
    }
}
