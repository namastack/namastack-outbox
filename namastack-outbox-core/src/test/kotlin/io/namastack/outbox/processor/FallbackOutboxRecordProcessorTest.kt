package io.namastack.outbox.processor

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.namastack.outbox.OutboxProperties
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.OutboxRecordStatus
import io.namastack.outbox.OutboxRecordTestFactory.outboxRecord
import io.namastack.outbox.handler.OutboxFailureContext
import io.namastack.outbox.handler.invoker.OutboxFallbackHandlerInvoker
import io.namastack.outbox.retry.OutboxRetryPolicy
import io.namastack.outbox.retry.OutboxRetryPolicyRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class FallbackOutboxRecordProcessorTest {
    private lateinit var recordRepository: OutboxRecordRepository
    private lateinit var fallbackHandlerInvoker: OutboxFallbackHandlerInvoker
    private lateinit var retryPolicyRegistry: OutboxRetryPolicyRegistry
    private lateinit var properties: OutboxProperties
    private lateinit var clock: Clock
    private lateinit var processor: FallbackOutboxRecordProcessor
    private lateinit var nextProcessor: OutboxRecordProcessor
    private lateinit var retryPolicy: OutboxRetryPolicy

    @BeforeEach
    fun setUp() {
        recordRepository = mockk()
        fallbackHandlerInvoker = mockk()
        retryPolicyRegistry = mockk()
        properties = OutboxProperties()
        clock = Clock.fixed(Instant.parse("2024-01-01T10:00:00Z"), ZoneOffset.UTC)
        nextProcessor = mockk()
        retryPolicy = mockk()

        processor =
            FallbackOutboxRecordProcessor(
                recordRepository,
                fallbackHandlerInvoker,
                retryPolicyRegistry,
                properties,
                clock,
            )
        processor.setNext(nextProcessor)

        every { retryPolicyRegistry.getByHandlerId(any()) } returns retryPolicy
    }

    @Test
    fun `handle dispatches to fallback handler and marks record as COMPLETED when deleteCompletedRecords is false`() {
        val record =
            outboxRecord(
                handlerId = "test-handler",
                failureCount = 3,
                failureException = RuntimeException("Handler failed"),
            )
        properties.processing.deleteCompletedRecords = false

        every { fallbackHandlerInvoker.dispatch(any(), any<OutboxFailureContext>()) } returns true
        every { retryPolicy.maxRetries() } returns 3
        every { retryPolicy.shouldRetry(any()) } returns true
        every { recordRepository.save(any() as OutboxRecord<*>) } returns record

        val result = processor.handle(record)

        assertThat(result).isTrue()
        assertThat(record.status).isEqualTo(OutboxRecordStatus.COMPLETED)
        assertThat(record.completedAt).isEqualTo(Instant.now(clock))

        verify { fallbackHandlerInvoker.dispatch(any(), any<OutboxFailureContext>()) }
        verify { recordRepository.save(record) }
        verify(exactly = 0) { recordRepository.deleteById(any()) }
        verify(exactly = 0) { nextProcessor.handle(any()) }
    }

    @Test
    fun `handle dispatches to fallback handler and deletes record when deleteCompletedRecords is true`() {
        val record =
            outboxRecord(
                handlerId = "test-handler",
                failureCount = 3,
                failureException = RuntimeException("Handler failed"),
            )
        properties.processing.deleteCompletedRecords = true

        every { fallbackHandlerInvoker.dispatch(any(), any<OutboxFailureContext>()) } returns true
        every { retryPolicy.maxRetries() } returns 3
        every { retryPolicy.shouldRetry(any()) } returns true
        justRun { recordRepository.deleteById(any()) }

        val result = processor.handle(record)

        assertThat(result).isTrue()

        verify { fallbackHandlerInvoker.dispatch(any(), any<OutboxFailureContext>()) }
        verify { recordRepository.deleteById(record.id) }
        verify(exactly = 0) { recordRepository.save(any() as OutboxRecord<*>) }
        verify(exactly = 0) { nextProcessor.handle(any()) }
    }

    @Test
    fun `handle passes to next processor when no fallback handler registered`() {
        val record =
            outboxRecord(
                handlerId = "test-handler",
                failureCount = 4,
                failureException = RuntimeException("Handler failed"),
            )

        every { fallbackHandlerInvoker.dispatch(any(), any<OutboxFailureContext>()) } returns false
        every { retryPolicy.maxRetries() } returns 3
        every { retryPolicy.shouldRetry(any()) } returns true
        every { nextProcessor.handle(any()) } returns false

        val result = processor.handle(record)

        assertThat(result).isFalse()

        verify { fallbackHandlerInvoker.dispatch(any(), any<OutboxFailureContext>()) }
        verify { nextProcessor.handle(record) }
        verify(exactly = 0) { recordRepository.save(any() as OutboxRecord<*>) }
        verify(exactly = 0) { recordRepository.deleteById(any()) }
    }

    @Test
    fun `handle stores exception and passes to next processor when fallback handler throws exception`() {
        val record =
            outboxRecord(
                handlerId = "test-handler",
                failureCount = 4,
                failureException = RuntimeException("Original error"),
            )
        val fallbackException = IllegalStateException("Fallback failed")

        every { fallbackHandlerInvoker.dispatch(any(), any<OutboxFailureContext>()) } throws fallbackException
        every { retryPolicy.maxRetries() } returns 3
        every { retryPolicy.shouldRetry(any()) } returns true
        every { nextProcessor.handle(any()) } returns false

        val result = processor.handle(record)

        assertThat(result).isFalse()
        assertThat(record.failureException).isEqualTo(fallbackException)
        assertThat(record.failureReason).isEqualTo("Fallback failed")

        verify { fallbackHandlerInvoker.dispatch(any(), any<OutboxFailureContext>()) }
        verify { nextProcessor.handle(record) }
        verify(exactly = 0) { recordRepository.save(any() as OutboxRecord<*>) }
        verify(exactly = 0) { recordRepository.deleteById(any()) }
    }

    @Test
    fun `handle passes failure context with correct retriesExhausted flag`() {
        val record =
            outboxRecord(
                handlerId = "test-handler",
                failureCount = 6,
                failureException = RuntimeException("Error"),
            )
        properties.processing.deleteCompletedRecords = true

        val contextSlot = slot<OutboxFailureContext>()
        every { fallbackHandlerInvoker.dispatch(any(), capture(contextSlot)) } returns true
        every { retryPolicy.maxRetries() } returns 5
        every { retryPolicy.shouldRetry(any()) } returns true
        justRun { recordRepository.deleteById(any()) }

        processor.handle(record)

        assertThat(contextSlot.captured.retriesExhausted).isTrue()
        assertThat(contextSlot.captured.failureCount).isEqualTo(6)
    }

    @Test
    fun `handle passes failure context with correct nonRetryableException flag`() {
        val record =
            outboxRecord(
                handlerId = "test-handler",
                failureCount = 1,
                failureException = IllegalArgumentException("Non-retryable"),
            )
        properties.processing.deleteCompletedRecords = true

        val contextSlot = slot<OutboxFailureContext>()
        every { fallbackHandlerInvoker.dispatch(any(), capture(contextSlot)) } returns true
        every { retryPolicy.maxRetries() } returns 5
        every { retryPolicy.shouldRetry(any()) } returns false
        justRun { recordRepository.deleteById(any()) }

        processor.handle(record)

        assertThat(contextSlot.captured.nonRetryableException).isTrue()
        assertThat(contextSlot.captured.retriesExhausted).isFalse()
    }

    @Test
    fun `handle passes failure context with all record details`() {
        val createdAt = Instant.parse("2024-01-01T09:00:00Z")
        val record =
            outboxRecord(
                id = "record-123",
                recordKey = "order-456",
                handlerId = "orderHandler",
                failureCount = 3,
                failureException = RuntimeException("Test error"),
                createdAt = createdAt,
            )
        properties.processing.deleteCompletedRecords = true

        val contextSlot = slot<OutboxFailureContext>()
        every { fallbackHandlerInvoker.dispatch(any(), capture(contextSlot)) } returns true
        every { retryPolicy.maxRetries() } returns 5
        every { retryPolicy.shouldRetry(any()) } returns true
        justRun { recordRepository.deleteById(any()) }

        processor.handle(record)

        assertThat(contextSlot.captured.recordId).isEqualTo("record-123")
        assertThat(contextSlot.captured.recordKey).isEqualTo("order-456")
        assertThat(contextSlot.captured.handlerId).isEqualTo("orderHandler")
        assertThat(contextSlot.captured.failureCount).isEqualTo(3)
        assertThat(contextSlot.captured.createdAt).isEqualTo(createdAt)
        assertThat(contextSlot.captured.lastFailure).isInstanceOf(RuntimeException::class.java)
    }

    @Test
    fun `handle returns false and passes to next processor when failureException is null`() {
        val record =
            outboxRecord(
                handlerId = "test-handler",
                failureCount = 3,
                failureException = null,
            )

        every { nextProcessor.handle(any()) } returns false

        val result = processor.handle(record)

        assertThat(result).isFalse()
        assertThat(record.failureException).isInstanceOf(IllegalStateException::class.java)
        verify { nextProcessor.handle(record) }
    }

    @Test
    fun `handle returns false when no next processor and fallback not registered`() {
        val record =
            outboxRecord(
                handlerId = "test-handler",
                failureCount = 4,
                failureException = RuntimeException("Error"),
            )
        val processorWithoutNext =
            FallbackOutboxRecordProcessor(
                recordRepository,
                fallbackHandlerInvoker,
                retryPolicyRegistry,
                properties,
                clock,
            )

        every { fallbackHandlerInvoker.dispatch(any(), any<OutboxFailureContext>()) } returns false
        every { retryPolicy.maxRetries() } returns 3
        every { retryPolicy.shouldRetry(any()) } returns true

        val result = processorWithoutNext.handle(record)

        assertThat(result).isFalse()
    }

    @Test
    fun `handle passes to next processor when fallback returns true from next processor`() {
        val record =
            outboxRecord(
                handlerId = "test-handler",
                failureCount = 4,
                failureException = RuntimeException("Error"),
            )

        every { fallbackHandlerInvoker.dispatch(any(), any<OutboxFailureContext>()) } returns false
        every { retryPolicy.maxRetries() } returns 3
        every { retryPolicy.shouldRetry(any()) } returns true
        every { nextProcessor.handle(any()) } returns true

        val result = processor.handle(record)

        assertThat(result).isTrue()

        verify { nextProcessor.handle(record) }
    }
}
