package io.namastack.outbox.handler

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordTestFactory.outboxRecord
import io.namastack.outbox.context.OutboxContextPropagator
import io.namastack.outbox.handler.method.GenericHandlerMethod
import io.namastack.outbox.handler.method.TypedHandlerMethod
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Disabled
@DisplayName("OutboxHandlerInvoker")
class OutboxHandlerInvokerTest {
    private val handlerRegistry = mockk<OutboxHandlerRegistry>()
    private val propagators: List<OutboxContextPropagator> = emptyList()
    private lateinit var invoker: OutboxHandlerInvoker

    @BeforeEach
    fun setUp() {
        invoker = OutboxHandlerInvoker(handlerRegistry, propagators)
    }

    @Nested
    @DisplayName("dispatch()")
    inner class DispatchTests {
        private val now = OffsetDateTime.now(ZoneOffset.UTC)

        @Test
        fun `should dispatch to typed handler with payload`() {
            val payload = "test-payload"
            val record: OutboxRecord<*> =
                outboxRecord(
                    recordKey = "test-key",
                    payload = payload,
                    handlerId = "handler-1",
                    failureCount = 0,
                    createdAt = now,
                )
            val typedHandler = mockk<TypedHandlerMethod>()

            every { handlerRegistry.getHandlerById(record.handlerId) } returns typedHandler
            every { typedHandler.invoke(payload) } returns Unit

            invoker.dispatch(record)

            verify { typedHandler.invoke(payload) }
        }

        @Test
        fun `should dispatch to generic handler with payload and metadata`() {
            val payload = "test-payload"
            val record: OutboxRecord<*> =
                outboxRecord(
                    recordKey = "test-key",
                    payload = payload,
                    handlerId = "handler-2",
                    failureCount = 0,
                    createdAt = now,
                )
            val metadata = OutboxRecordMetadata(record.key, record.handlerId, record.createdAt)
            val genericHandler = mockk<GenericHandlerMethod>()

            every { handlerRegistry.getHandlerById(record.handlerId) } returns genericHandler
            every { genericHandler.invoke(payload, metadata) } returns Unit

            invoker.dispatch(record)

            verify { genericHandler.invoke(payload, metadata) }
        }

        @Test
        fun `should skip processing when payload is null`() {
            val record: OutboxRecord<*> =
                outboxRecord(
                    recordKey = "test-key",
                    payload = null,
                    handlerId = "handler-1",
                    failureCount = 0,
                    createdAt = now,
                )
            invoker.dispatch(record)

            verify(exactly = 0) { handlerRegistry.getHandlerById(any()) }
        }

        @Test
        fun `should throw exception when handler not found`() {
            val payload = "test-payload"
            val record: OutboxRecord<*> =
                outboxRecord(
                    recordKey = "test-key",
                    payload = payload,
                    handlerId = "unknown-handler",
                    failureCount = 0,
                    createdAt = now,
                )

            every { handlerRegistry.getHandlerById(record.handlerId) } returns null

            assertThatThrownBy {
                invoker.dispatch(record)
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("No handler with id unknown-handler")
        }

        @Test
        fun `should propagate handler exceptions`() {
            val payload = "test-payload"
            val record: OutboxRecord<*> =
                outboxRecord(
                    recordKey = "test-key",
                    payload = payload,
                    handlerId = "failing-handler",
                    failureCount = 0,
                    createdAt = now,
                )
            val typedHandler = mockk<TypedHandlerMethod>()
            val exception = RuntimeException("Handler error")

            every { handlerRegistry.getHandlerById(record.handlerId) } returns typedHandler
            every { typedHandler.invoke(payload) } throws exception

            assertThatThrownBy {
                invoker.dispatch(record)
            }.isInstanceOf(RuntimeException::class.java)
                .hasMessage("Handler error")
        }
    }

    @Nested
    @DisplayName("Handler Type Detection")
    inner class HandlerTypeDetectionTests {
        private val now = OffsetDateTime.now(ZoneOffset.UTC)

        @Test
        fun `should correctly identify and invoke typed handler`() {
            val payload = 42
            val record: OutboxRecord<*> =
                outboxRecord(
                    recordKey = "test-key",
                    payload = payload,
                    handlerId = "typed-handler",
                    failureCount = 0,
                    createdAt = now,
                )
            val typedHandler = mockk<TypedHandlerMethod>()
            val invokeSlot = slot<Any>()

            every { handlerRegistry.getHandlerById(record.handlerId) } returns typedHandler
            every { typedHandler.invoke(capture(invokeSlot)) } returns Unit

            invoker.dispatch(record)

            assertThat(invokeSlot.captured).isEqualTo(42)
            verify(exactly = 1) { typedHandler.invoke(any()) }
        }

        @Test
        fun `should correctly identify and invoke generic handler`() {
            val payload: Any = mapOf("key" to "value")
            val record: OutboxRecord<*> =
                outboxRecord(
                    recordKey = "test-key",
                    payload = payload,
                    handlerId = "generic-handler",
                    failureCount = 0,
                    createdAt = now,
                )
            val metadata = OutboxRecordMetadata(record.key, record.handlerId, record.createdAt)
            val genericHandler = mockk<GenericHandlerMethod>()
            val payloadSlot = slot<Any>()
            val metadataSlot = slot<OutboxRecordMetadata>()

            every { handlerRegistry.getHandlerById(record.handlerId) } returns genericHandler
            every { genericHandler.invoke(capture(payloadSlot), capture(metadataSlot)) } returns Unit

            invoker.dispatch(record)

            assertThat(payloadSlot.captured).isEqualTo(payload)
            assertThat(metadataSlot.captured).isEqualTo(metadata)
            verify(exactly = 1) { genericHandler.invoke(any(), any()) }
        }
    }

    @Nested
    @DisplayName("Handler Lookup")
    inner class HandlerLookupTests {
        private val now = OffsetDateTime.now(ZoneOffset.UTC)

        @Test
        fun `should look up handler by ID from metadata`() {
            val payload = "test"
            val record: OutboxRecord<*> =
                outboxRecord(
                    recordKey = "test-key",
                    payload = payload,
                    handlerId = "my.custom.Handler#handle(java.lang.String)",
                    failureCount = 0,
                    createdAt = now,
                )
            val handler = mockk<TypedHandlerMethod>()

            every { handlerRegistry.getHandlerById(record.handlerId) } returns handler
            every { handler.invoke(any()) } returns Unit

            invoker.dispatch(record)

            verify { handlerRegistry.getHandlerById(record.handlerId) }
        }

        @Test
        fun `should throw exception with handler ID in error message`() {
            val payload = "test"
            val record: OutboxRecord<*> =
                outboxRecord(
                    recordKey = "test-key",
                    payload = payload,
                    handlerId = "missing.Handler#handle()",
                    failureCount = 0,
                    createdAt = now,
                )

            every { handlerRegistry.getHandlerById(record.handlerId) } returns null

            assertThatThrownBy {
                invoker.dispatch(record)
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining(record.handlerId)
        }
    }

    @Nested
    @DisplayName("Multiple Payloads and Handlers")
    inner class MultiplePayloadsTests {
        private val now = OffsetDateTime.now(ZoneOffset.UTC)

        @Test
        fun `should handle different payload types sequentially`() {
            val handler = mockk<TypedHandlerMethod>()

            every { handlerRegistry.getHandlerById("handler-1") } returns handler
            every { handler.invoke(any()) } returns Unit

            invoker.dispatch(
                outboxRecord(
                    recordKey = "key-1",
                    payload = "string-payload",
                    handlerId = "handler-1",
                    failureCount = 0,
                    createdAt = now,
                ),
            )
            invoker.dispatch(
                outboxRecord(
                    recordKey = "key-2",
                    payload = 123,
                    handlerId = "handler-1",
                    failureCount = 0,
                    createdAt = now,
                ),
            )
            invoker.dispatch(
                outboxRecord(
                    recordKey = "key-3",
                    payload = listOf(1, 2, 3),
                    handlerId = "handler-1",
                    failureCount = 0,
                    createdAt = now,
                ),
            )

            verify(exactly = 3) { handler.invoke(any()) }
        }

        @Test
        fun `should invoke different handlers for different handler IDs`() {
            val handler1 = mockk<TypedHandlerMethod>()
            val handler2 = mockk<GenericHandlerMethod>()
            val payload = "test"

            every { handlerRegistry.getHandlerById("handler-1") } returns handler1
            every { handlerRegistry.getHandlerById("handler-2") } returns handler2
            every { handler1.invoke(any()) } returns Unit
            every { handler2.invoke(any(), any()) } returns Unit

            invoker.dispatch(
                outboxRecord(
                    recordKey = "key-1",
                    payload = listOf(1, 2, 3),
                    handlerId = "handler-1",
                    failureCount = 0,
                    createdAt = now,
                ),
            )
            invoker.dispatch(
                outboxRecord(
                    recordKey = "key-2",
                    payload = listOf(1, 2, 3),
                    handlerId = "handler-2",
                    failureCount = 0,
                    createdAt = now,
                ),
            )

            verify { handler1.invoke(payload) }
            verify { handler2.invoke(payload, any()) }
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCasesTests {
        private val now = OffsetDateTime.now(ZoneOffset.UTC)

        @Test
        fun `should handle empty string payload`() {
            val payload = ""
            val record: OutboxRecord<*> =
                outboxRecord(
                    recordKey = "test-key",
                    payload = payload,
                    handlerId = "handler-1",
                    failureCount = 0,
                    createdAt = now,
                )
            val handler = mockk<TypedHandlerMethod>()

            every { handlerRegistry.getHandlerById(record.handlerId) } returns handler
            every { handler.invoke(payload) } returns Unit

            invoker.dispatch(record)

            verify { handler.invoke("") }
        }

        @Test
        fun `should handle complex object payload`() {
            val payload = ComplexPayload(id = "123", data = mapOf("nested" to listOf(1, 2, 3)))
            val record: OutboxRecord<*> =
                outboxRecord(
                    recordKey = "test-key",
                    payload = payload,
                    handlerId = "handler-1",
                    failureCount = 0,
                    createdAt = now,
                )
            val handler = mockk<TypedHandlerMethod>()

            every { handlerRegistry.getHandlerById(record.handlerId) } returns handler
            every { handler.invoke(payload) } returns Unit

            invoker.dispatch(record)

            verify { handler.invoke(payload) }
        }

        @Test
        fun `should handle handler that modifies state`() {
            val record: OutboxRecord<*> =
                outboxRecord(
                    recordKey = "test-key",
                    payload = "payload",
                    handlerId = "handler-1",
                    failureCount = 0,
                    createdAt = now,
                )
            val state = mutableListOf<String>()
            val handler = mockk<TypedHandlerMethod>()

            every { handlerRegistry.getHandlerById(record.handlerId) } returns handler
            every { handler.invoke(any()) } answers {
                state.add("processed")
            }

            invoker.dispatch(record)

            assertThat(state).contains("processed")
        }
    }

    data class ComplexPayload(
        val id: String,
        val data: Map<String, Any>,
    )
}
