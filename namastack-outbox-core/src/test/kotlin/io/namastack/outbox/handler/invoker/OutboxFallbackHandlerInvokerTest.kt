package io.namastack.outbox.handler.invoker

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import io.namastack.outbox.handler.OutboxFailureContext
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.handler.method.fallback.OutboxFallbackHandlerMethod
import io.namastack.outbox.handler.registry.OutboxFallbackHandlerRegistry
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@DisplayName("OutboxFallbackHandlerInvoker")
class OutboxFallbackHandlerInvokerTest {
    private val fallbackHandlerRegistry = mockk<OutboxFallbackHandlerRegistry>()
    private lateinit var invoker: OutboxFallbackHandlerInvoker
    private val now = OffsetDateTime.now(ZoneOffset.UTC)

    @BeforeEach
    fun setUp() {
        invoker = OutboxFallbackHandlerInvoker(fallbackHandlerRegistry)
    }

    @Test
    fun `dispatches to fallback handler with payload, metadata and context`() {
        val payload = "test-payload"
        val metadata = OutboxRecordMetadata("test-key", "handler-1", now)
        val context =
            OutboxFailureContext(
                failureCount = 3,
                recordId = UUID.randomUUID().toString(),
                lastFailureReason = "Test failure",
                handlerId = "handler-id",
                retriesExhausted = true,
                nonRetryableException = false,
            )
        val fallbackHandler = mockk<OutboxFallbackHandlerMethod>()

        every { fallbackHandlerRegistry.getByHandlerId("handler-1") } returns fallbackHandler
        every { fallbackHandler.invoke(payload, metadata, context) } just runs

        invoker.dispatch(payload, metadata, context)

        verify { fallbackHandler.invoke(payload, metadata, context) }
    }

    @Test
    fun `skips processing when payload is null`() {
        val metadata = OutboxRecordMetadata("test-key", "handler-1", now)
        val context =
            OutboxFailureContext(
                failureCount = 3,
                recordId = UUID.randomUUID().toString(),
                lastFailureReason = "Test failure",
                handlerId = "handler-id",
                retriesExhausted = true,
                nonRetryableException = false,
            )

        invoker.dispatch(null, metadata, context)

        verify(exactly = 0) { fallbackHandlerRegistry.getByHandlerId(any()) }
    }

    @Test
    fun `skips processing when no fallback handler registered`() {
        val payload = "test-payload"
        val metadata = OutboxRecordMetadata("test-key", "handler-without-fallback", now)
        val context =
            OutboxFailureContext(
                failureCount = 3,
                recordId = UUID.randomUUID().toString(),
                lastFailureReason = "Test failure",
                handlerId = "handler-id",
                retriesExhausted = true,
                nonRetryableException = false,
            )

        every { fallbackHandlerRegistry.getByHandlerId("handler-without-fallback") } returns null

        invoker.dispatch(payload, metadata, context)

        verify { fallbackHandlerRegistry.getByHandlerId("handler-without-fallback") }
    }

    @Test
    fun `propagates exception from fallback handler`() {
        val payload = "test-payload"
        val metadata = OutboxRecordMetadata("test-key", "failing-handler", now)
        val context =
            OutboxFailureContext(
                failureCount = 3,
                recordId = UUID.randomUUID().toString(),
                lastFailureReason = "Test failure",
                handlerId = "handler-id",
                retriesExhausted = true,
                nonRetryableException = false,
            )
        val fallbackHandler = mockk<OutboxFallbackHandlerMethod>()
        val exception = RuntimeException("Fallback handler error")

        every { fallbackHandlerRegistry.getByHandlerId("failing-handler") } returns fallbackHandler
        every { fallbackHandler.invoke(payload, metadata, context) } throws exception

        assertThatThrownBy {
            invoker.dispatch(payload, metadata, context)
        }.isSameAs(exception)
    }

    @Test
    fun `looks up fallback handler by ID from metadata`() {
        val payload = "test"
        val handlerId = "my.custom.Handler#handleFallback"
        val metadata = OutboxRecordMetadata("test-key", handlerId, now)
        val context =
            OutboxFailureContext(
                failureCount = 3,
                recordId = UUID.randomUUID().toString(),
                lastFailureReason = "Test failure",
                handlerId = "handler-id",
                retriesExhausted = true,
                nonRetryableException = false,
            )
        val fallbackHandler = mockk<OutboxFallbackHandlerMethod>()

        every { fallbackHandlerRegistry.getByHandlerId(handlerId) } returns fallbackHandler
        every { fallbackHandler.invoke(any(), any(), any()) } just runs

        invoker.dispatch(payload, metadata, context)

        verify { fallbackHandlerRegistry.getByHandlerId(handlerId) }
    }
}
