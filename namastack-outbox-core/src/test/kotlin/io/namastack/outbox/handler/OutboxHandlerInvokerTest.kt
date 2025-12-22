package io.namastack.outbox.handler

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.handler.invoker.OutboxHandlerInvoker
import io.namastack.outbox.handler.method.handler.GenericHandlerMethod
import io.namastack.outbox.handler.method.handler.TypedHandlerMethod
import io.namastack.outbox.handler.registry.OutboxHandlerRegistry
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset

@DisplayName("OutboxHandlerInvoker")
class OutboxHandlerInvokerTest {
    private val handlerRegistry = mockk<OutboxHandlerRegistry>()
    private lateinit var invoker: OutboxHandlerInvoker
    private val now = OffsetDateTime.now(ZoneOffset.UTC)

    @BeforeEach
    fun setUp() {
        invoker = OutboxHandlerInvoker(handlerRegistry)
    }

    @Test
    fun `dispatches to typed handler with payload only`() {
        val payload = "test-payload"
        val metadata = OutboxRecordMetadata("test-key", "handler-1", now)
        val typedHandler = mockk<TypedHandlerMethod>()

        every { handlerRegistry.getHandlerById("handler-1") } returns typedHandler
        every { typedHandler.invoke(payload) } returns Unit

        invoker.dispatch(payload, metadata)

        verify { typedHandler.invoke(payload) }
    }

    @Test
    fun `dispatches to generic handler with payload and metadata`() {
        val payload: Any = "test-payload"
        val metadata = OutboxRecordMetadata("test-key", "handler-2", now)
        val genericHandler = mockk<GenericHandlerMethod>()

        every { handlerRegistry.getHandlerById("handler-2") } returns genericHandler
        every { genericHandler.invoke(payload, metadata) } returns Unit

        invoker.dispatch(payload, metadata)

        verify { genericHandler.invoke(payload, metadata) }
    }

    @Test
    fun `skips processing when payload is null`() {
        val metadata = OutboxRecordMetadata("test-key", "handler-1", now)

        invoker.dispatch(null, metadata)

        verify(exactly = 0) { handlerRegistry.getHandlerById(any()) }
    }

    @Test
    fun `throws IllegalStateException when handler not found`() {
        val payload = "test-payload"
        val metadata = OutboxRecordMetadata("test-key", "unknown-handler", now)

        every { handlerRegistry.getHandlerById("unknown-handler") } returns null

        assertThatThrownBy {
            invoker.dispatch(payload, metadata)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No handler with id unknown-handler")
    }

    @Test
    fun `propagates exception from typed handler`() {
        val payload = "test-payload"
        val metadata = OutboxRecordMetadata("test-key", "failing-handler", now)
        val typedHandler = mockk<TypedHandlerMethod>()
        val exception = RuntimeException("Handler error")

        every { handlerRegistry.getHandlerById("failing-handler") } returns typedHandler
        every { typedHandler.invoke(payload) } throws exception

        assertThatThrownBy {
            invoker.dispatch(payload, metadata)
        }.isSameAs(exception)
    }

    @Test
    fun `propagates exception from generic handler`() {
        val payload: Any = "test-payload"
        val metadata = OutboxRecordMetadata("test-key", "failing-handler", now)
        val genericHandler = mockk<GenericHandlerMethod>()
        val exception = IllegalStateException("Handler error")

        every { handlerRegistry.getHandlerById("failing-handler") } returns genericHandler
        every { genericHandler.invoke(payload, metadata) } throws exception

        assertThatThrownBy {
            invoker.dispatch(payload, metadata)
        }.isSameAs(exception)
    }

    @Test
    fun `looks up handler by ID from metadata`() {
        val payload = "test"
        val handlerId = "my.custom.Handler#handle(java.lang.String)"
        val metadata = OutboxRecordMetadata("test-key", handlerId, now)
        val handler = mockk<TypedHandlerMethod>()

        every { handlerRegistry.getHandlerById(handlerId) } returns handler
        every { handler.invoke(any()) } returns Unit

        invoker.dispatch(payload, metadata)

        verify { handlerRegistry.getHandlerById(handlerId) }
    }
}
