package io.namastack.outbox.handler.invoker

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import io.namastack.outbox.handler.OutboxFailureContext
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
        val context = createOutboxFailureContext()
        val fallbackHandler = mockk<OutboxFallbackHandlerMethod>()

        every { fallbackHandlerRegistry.getByHandlerId("handler-id") } returns fallbackHandler
        every { fallbackHandler.invoke(payload, context) } just runs

        invoker.dispatch(payload, context)

        verify { fallbackHandler.invoke(payload, context) }
    }

    @Test
    fun `skips processing when payload is null`() {
        val context = createOutboxFailureContext()

        invoker.dispatch(null, context)

        verify(exactly = 0) { fallbackHandlerRegistry.getByHandlerId(any()) }
    }

    @Test
    fun `skips processing when no fallback handler registered`() {
        val payload = "test-payload"
        val handlerId = "handler-without-fallback"
        val context = createOutboxFailureContext(handlerId = handlerId)

        every { fallbackHandlerRegistry.getByHandlerId(handlerId) } returns null

        invoker.dispatch(payload, context)

        verify { fallbackHandlerRegistry.getByHandlerId(handlerId) }
    }

    @Test
    fun `propagates exception from fallback handler`() {
        val payload = "test-payload"
        val context = createOutboxFailureContext(handlerId = "failing-handler")
        val fallbackHandler = mockk<OutboxFallbackHandlerMethod>()
        val exception = RuntimeException("Fallback handler error")

        every { fallbackHandlerRegistry.getByHandlerId("failing-handler") } returns fallbackHandler
        every { fallbackHandler.invoke(payload, context) } throws exception

        assertThatThrownBy {
            invoker.dispatch(payload, context)
        }.isSameAs(exception)
    }

    @Test
    fun `looks up fallback handler by ID from metadata`() {
        val payload = "test"
        val handlerId = "my.custom.Handler#handleFallback"
        val context = createOutboxFailureContext(handlerId = handlerId)
        val fallbackHandler = mockk<OutboxFallbackHandlerMethod>()

        every { fallbackHandlerRegistry.getByHandlerId(handlerId) } returns fallbackHandler
        every { fallbackHandler.invoke(any(), any()) } just runs

        invoker.dispatch(payload, context)

        verify { fallbackHandlerRegistry.getByHandlerId(handlerId) }
    }

    private fun createOutboxFailureContext(
        failureCount: Int = 3,
        recordId: String = UUID.randomUUID().toString(),
        handlerId: String = "handler-id",
        retriesExhausted: Boolean = true,
        nonRetryableException: Boolean = false,
        recordKey: String = "key",
        createdAt: OffsetDateTime = now,
        lastFailure: Throwable? = null,
    ): OutboxFailureContext =
        OutboxFailureContext(
            failureCount = failureCount,
            recordId = recordId,
            handlerId = handlerId,
            retriesExhausted = retriesExhausted,
            nonRetryableException = nonRetryableException,
            recordKey = recordKey,
            createdAt = createdAt,
            lastFailure = lastFailure,
        )
}
