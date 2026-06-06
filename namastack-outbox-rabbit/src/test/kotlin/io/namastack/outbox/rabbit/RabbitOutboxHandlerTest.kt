package io.namastack.outbox.rabbit

import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.namastack.outbox.handler.OutboxRecordMetadata
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

@DisplayName("RabbitOutboxHandler")
class RabbitOutboxHandlerTest {
    private lateinit var publisher: RabbitOutboxPublisher
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
        publisher = mockk(relaxed = true)
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
