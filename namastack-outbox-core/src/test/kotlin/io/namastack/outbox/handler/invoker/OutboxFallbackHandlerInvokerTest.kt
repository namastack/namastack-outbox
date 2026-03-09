package io.namastack.outbox.handler.invoker

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordStatus
import io.namastack.outbox.handler.OutboxFailureContext
import io.namastack.outbox.handler.method.fallback.OutboxFallbackHandlerMethod
import io.namastack.outbox.handler.registry.OutboxFallbackHandlerRegistry
import io.namastack.outbox.retry.OutboxRetryPolicy
import io.namastack.outbox.retry.OutboxRetryPolicyRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

@DisplayName("OutboxFallbackHandlerInvoker")
class OutboxFallbackHandlerInvokerTest {
    private val retryPolicyRegistry = mockk<OutboxRetryPolicyRegistry>()
    private val fallbackHandlerRegistry = mockk<OutboxFallbackHandlerRegistry>()
    private lateinit var invoker: OutboxFallbackHandlerInvoker
    private val now = Instant.now()
    private val retryPolicy = mockk<OutboxRetryPolicy>()

    @BeforeEach
    fun setUp() {
        invoker = OutboxFallbackHandlerInvoker(retryPolicyRegistry, fallbackHandlerRegistry)
        every { retryPolicyRegistry.getByHandlerId(any()) } returns retryPolicy
        every { retryPolicy.maxRetries() } returns 3
        every { retryPolicy.shouldRetry(any()) } returns true
    }

    @Test
    fun `dispatches to fallback handler with payload and context`() {
        val (record, context) = createRecord(handlerId = "handler-id")
        val fallbackHandler = mockk<OutboxFallbackHandlerMethod>()

        every { fallbackHandlerRegistry.getByHandlerId("handler-id") } returns fallbackHandler
        every { fallbackHandler.invoke(any(), any()) } just runs

        val rs = invoker.dispatch(record)

        assertThat(rs).isTrue
        verify { fallbackHandler.invoke(record.payload!!, context) }
    }

    @Test
    fun `skips processing when payload is null`() {
        val (record, _) = createRecord(payload = null)

        val rs = invoker.dispatch(record)

        assertThat(rs).isFalse
        verify(exactly = 0) { fallbackHandlerRegistry.getByHandlerId(any()) }
    }

    @Test
    fun `skips processing when failureException is null`() {
        val (record, _) = createRecord(lastFailure = null)

        assertThatThrownBy {
            invoker.dispatch(record)
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `skips processing when no fallback handler registered`() {
        val (record, _) = createRecord(handlerId = "handler-without-fallback")

        every { fallbackHandlerRegistry.getByHandlerId("handler-without-fallback") } returns null

        val rs = invoker.dispatch(record)

        assertThat(rs).isFalse
        verify { fallbackHandlerRegistry.getByHandlerId("handler-without-fallback") }
    }

    @Test
    fun `propagates exception from fallback handler`() {
        val (record, _) = createRecord(handlerId = "failing-handler")
        val fallbackHandler = mockk<OutboxFallbackHandlerMethod>()
        val exception = RuntimeException("Fallback handler error")

        every { fallbackHandlerRegistry.getByHandlerId("failing-handler") } returns fallbackHandler
        every { fallbackHandler.invoke(any(), any()) } throws exception

        assertThatThrownBy {
            invoker.dispatch(record)
        }.isSameAs(exception)
    }

    private fun createRecord(
        id: String = "test-payload",
        key: String = "test-key",
        handlerId: String = "handler-1",
        payload: Any? = "test-payload",
        failureCount: Int = 4,
        retriesExhausted: Boolean = true,
        nonRetryableException: Boolean = false,
        lastFailure: Throwable? = RuntimeException("Handler failed"),
    ): Pair<OutboxRecord<Any?>, OutboxFailureContext> {
        val record =
            OutboxRecord.restore(
                id = id,
                recordKey = key,
                payload = payload,
                context = emptyMap(),
                createdAt = now,
                status = OutboxRecordStatus.NEW,
                completedAt = null,
                failureCount = failureCount,
                failureException = lastFailure,
                failureReason = null,
                partition = 1,
                nextRetryAt = now,
                handlerId = handlerId,
            )
        val context =
            OutboxFailureContext(
                recordId = id,
                recordKey = key,
                createdAt = now,
                failureCount = failureCount,
                lastFailure = lastFailure,
                handlerId = handlerId,
                retriesExhausted = retriesExhausted,
                nonRetryableException = nonRetryableException,
                context = emptyMap(),
            )
        return record to context
    }
}
