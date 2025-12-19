package io.namastack.outbox.handler

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.namastack.outbox.handler.method.GenericHandlerMethod
import io.namastack.outbox.handler.method.TypedHandlerMethod
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset

@DisplayName("OutboxHandlerInvoker")
class OutboxHandlerInvokerTest {
    private val handlerRegistry = mockk<OutboxHandlerRegistry>()
    private lateinit var invoker: OutboxHandlerInvoker

    @BeforeEach
    fun setUp() {
        invoker = OutboxHandlerInvoker(handlerRegistry)
    }

    @Nested
    @DisplayName("dispatch()")
    inner class DispatchTests {
        private val now = OffsetDateTime.now(ZoneOffset.UTC)

        @Test
        fun `should dispatch to typed handler with payload`() {
            val payload = "test-payload"
            val metadata = OutboxRecordMetadata("test-key", "handler-1", now)
            val typedHandler = mockk<TypedHandlerMethod>()

            every { handlerRegistry.getHandlerById("handler-1") } returns typedHandler
            every { typedHandler.invoke(payload) } returns Unit

            invoker.dispatch(payload, metadata)

            verify { typedHandler.invoke(payload) }
        }

        @Test
        fun `should dispatch to generic handler with payload and metadata`() {
            val payload: Any = "test-payload"
            val metadata = OutboxRecordMetadata("test-key", "handler-2", now)
            val genericHandler = mockk<GenericHandlerMethod>()

            every { handlerRegistry.getHandlerById("handler-2") } returns genericHandler
            every { genericHandler.invoke(payload, metadata) } returns Unit

            invoker.dispatch(payload, metadata)

            verify { genericHandler.invoke(payload, metadata) }
        }

        @Test
        fun `should skip processing when payload is null`() {
            val metadata = OutboxRecordMetadata("test-key", "handler-1", now)

            invoker.dispatch(null, metadata)

            verify(exactly = 0) { handlerRegistry.getHandlerById(any()) }
        }

        @Test
        fun `should throw exception when handler not found`() {
            val payload = "test-payload"
            val metadata = OutboxRecordMetadata("test-key", "unknown-handler", now)

            every { handlerRegistry.getHandlerById("unknown-handler") } returns null

            assertThatThrownBy {
                invoker.dispatch(payload, metadata)
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("No handler with id unknown-handler")
        }

        @Test
        fun `should propagate handler exceptions`() {
            val payload = "test-payload"
            val metadata = OutboxRecordMetadata("test-key", "failing-handler", now)
            val typedHandler = mockk<TypedHandlerMethod>()
            val exception = RuntimeException("Handler error")

            every { handlerRegistry.getHandlerById("failing-handler") } returns typedHandler
            every { typedHandler.invoke(payload) } throws exception

            assertThatThrownBy {
                invoker.dispatch(payload, metadata)
            }.isInstanceOf(RuntimeException::class.java)
                .hasMessage("Handler error")
        }

        @Test
        fun `should propagate original exception from typed handler`() {
            val payload = "test-payload"
            val metadata = OutboxRecordMetadata("test-key", "failing-handler", now)
            val typedHandler = mockk<TypedHandlerMethod>()
            val originalException = IllegalArgumentException("Invalid argument")

            every { handlerRegistry.getHandlerById("failing-handler") } returns typedHandler
            every { typedHandler.invoke(payload) } throws originalException

            assertThatThrownBy {
                invoker.dispatch(payload, metadata)
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Invalid argument")
        }

        @Test
        fun `should propagate original exception from generic handler`() {
            val payload: Any = "test-payload"
            val metadata = OutboxRecordMetadata("test-key", "failing-handler", now)
            val genericHandler = mockk<GenericHandlerMethod>()
            val originalException = IllegalStateException("Invalid state")

            every { handlerRegistry.getHandlerById("failing-handler") } returns genericHandler
            every { genericHandler.invoke(payload, metadata) } throws originalException

            assertThatThrownBy {
                invoker.dispatch(payload, metadata)
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessage("Invalid state")
        }
    }

    @Nested
    @DisplayName("Handler Type Detection")
    inner class HandlerTypeDetectionTests {
        private val now = OffsetDateTime.now(ZoneOffset.UTC)

        @Test
        fun `should correctly identify and invoke typed handler`() {
            val payload = 42
            val metadata = OutboxRecordMetadata("test-key", "typed-handler", now)
            val typedHandler = mockk<TypedHandlerMethod>()
            val invokeSlot = slot<Any>()

            every { handlerRegistry.getHandlerById("typed-handler") } returns typedHandler
            every { typedHandler.invoke(capture(invokeSlot)) } returns Unit

            invoker.dispatch(payload, metadata)

            assertThat(invokeSlot.captured).isEqualTo(42)
            verify(exactly = 1) { typedHandler.invoke(any()) }
        }

        @Test
        fun `should correctly identify and invoke generic handler`() {
            val payload: Any = mapOf("key" to "value")
            val metadata = OutboxRecordMetadata("test-key", "generic-handler", now)
            val genericHandler = mockk<GenericHandlerMethod>()
            val payloadSlot = slot<Any>()
            val metadataSlot = slot<OutboxRecordMetadata>()

            every { handlerRegistry.getHandlerById("generic-handler") } returns genericHandler
            every { genericHandler.invoke(capture(payloadSlot), capture(metadataSlot)) } returns Unit

            invoker.dispatch(payload, metadata)

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
            val handlerId = "my.custom.Handler#handle(java.lang.String)"
            val metadata = OutboxRecordMetadata("test-key", handlerId, now)
            val handler = mockk<TypedHandlerMethod>()

            every { handlerRegistry.getHandlerById(handlerId) } returns handler
            every { handler.invoke(any()) } returns Unit

            invoker.dispatch(payload, metadata)

            verify { handlerRegistry.getHandlerById(handlerId) }
        }

        @Test
        fun `should throw exception with handler ID in error message`() {
            val payload = "test"
            val handlerId = "missing.Handler#handle()"
            val metadata = OutboxRecordMetadata("test-key", handlerId, now)

            every { handlerRegistry.getHandlerById(handlerId) } returns null

            assertThatThrownBy {
                invoker.dispatch(payload, metadata)
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining(handlerId)
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

            val payload1 = "string-payload"
            val payload2 = 123
            val payload3 = listOf(1, 2, 3)

            invoker.dispatch(payload1, OutboxRecordMetadata("key-1", "handler-1", now))
            invoker.dispatch(payload2, OutboxRecordMetadata("key-2", "handler-1", now))
            invoker.dispatch(payload3, OutboxRecordMetadata("key-3", "handler-1", now))

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

            invoker.dispatch(payload, OutboxRecordMetadata("key-1", "handler-1", now))
            invoker.dispatch(payload, OutboxRecordMetadata("key-2", "handler-2", now))

            verify { handler1.invoke(payload) }
            verify { handler2.invoke(payload, any()) }
        }
    }

    @Nested
    @DisplayName("Exception Unwrapping")
    inner class ExceptionUnwrappingTests {
        private val now = OffsetDateTime.now(ZoneOffset.UTC)

        @Test
        fun `should unwrap InvocationTargetException from typed handler`() {
            val bean = TestTypedHandlerForUnwrapping()
            val method = bean::class.java.getMethod("handleWithException", String::class.java)
            val typedHandler = TypedHandlerMethod(bean, method, String::class)
            val metadata = OutboxRecordMetadata("test-key", "handler-1", now)

            every { handlerRegistry.getHandlerById("handler-1") } returns typedHandler

            assertThatThrownBy {
                invoker.dispatch("test-payload", metadata)
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("Original exception from typed handler")
                .hasMessageContaining("test-payload")
        }

        @Test
        fun `should unwrap InvocationTargetException from generic handler`() {
            val bean = TestGenericHandlerForUnwrapping()
            val method =
                bean::class.java.getMethod(
                    "handleWithException",
                    Any::class.java,
                    OutboxRecordMetadata::class.java,
                )
            val genericHandler = GenericHandlerMethod(bean, method)
            val metadata = OutboxRecordMetadata("test-key", "handler-2", now)

            every { handlerRegistry.getHandlerById("handler-2") } returns genericHandler

            assertThatThrownBy {
                invoker.dispatch("test-payload", metadata)
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Original exception from generic handler")
                .hasMessageContaining("test-key")
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCasesTests {
        private val now = OffsetDateTime.now(ZoneOffset.UTC)

        @Test
        fun `should handle empty string payload`() {
            val payload = ""
            val metadata = OutboxRecordMetadata("test-key", "handler-1", now)
            val handler = mockk<TypedHandlerMethod>()

            every { handlerRegistry.getHandlerById("handler-1") } returns handler
            every { handler.invoke(payload) } returns Unit

            invoker.dispatch(payload, metadata)

            verify { handler.invoke("") }
        }

        @Test
        fun `should handle complex object payload`() {
            val payload = ComplexPayload(id = "123", data = mapOf("nested" to listOf(1, 2, 3)))
            val metadata = OutboxRecordMetadata("test-key", "handler-1", now)
            val handler = mockk<TypedHandlerMethod>()

            every { handlerRegistry.getHandlerById("handler-1") } returns handler
            every { handler.invoke(payload) } returns Unit

            invoker.dispatch(payload, metadata)

            verify { handler.invoke(payload) }
        }

        @Test
        fun `should handle handler that modifies state`() {
            val state = mutableListOf<String>()
            val metadata = OutboxRecordMetadata("test-key", "handler-1", now)
            val handler = mockk<TypedHandlerMethod>()

            every { handlerRegistry.getHandlerById("handler-1") } returns handler
            every { handler.invoke(any()) } answers {
                state.add("processed")
            }

            invoker.dispatch("payload", metadata)

            assertThat(state).contains("processed")
        }
    }

    data class ComplexPayload(
        val id: String,
        val data: Map<String, Any>,
    )

    class TestTypedHandlerForUnwrapping {
        fun handleWithException(payload: String): Unit =
            throw IllegalArgumentException("Original exception from typed handler: $payload")
    }

    class TestGenericHandlerForUnwrapping {
        fun handleWithException(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ): Unit = throw IllegalStateException("Original exception from generic handler: ${metadata.key}")
    }
}
