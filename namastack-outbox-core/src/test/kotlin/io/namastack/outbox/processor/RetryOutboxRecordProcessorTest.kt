package io.namastack.outbox.processor

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.OutboxRecordTestFactory.outboxRecord
import io.namastack.outbox.retry.OutboxRetryPolicy
import io.namastack.outbox.retry.OutboxRetryPolicyRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

class RetryOutboxRecordProcessorTest {
    private lateinit var retryPolicyRegistry: OutboxRetryPolicyRegistry
    private lateinit var recordRepository: OutboxRecordRepository
    private lateinit var clock: Clock
    private lateinit var processor: RetryOutboxRecordProcessor
    private lateinit var nextProcessor: OutboxRecordProcessor
    private lateinit var retryPolicy: OutboxRetryPolicy

    @BeforeEach
    fun setUp() {
        retryPolicyRegistry = mockk()
        recordRepository = mockk()
        clock = Clock.fixed(Instant.parse("2024-01-01T10:00:00Z"), ZoneOffset.UTC)
        nextProcessor = mockk()
        retryPolicy = mockk()

        processor = RetryOutboxRecordProcessor(retryPolicyRegistry, recordRepository, clock)
        processor.setNext(nextProcessor)

        every { retryPolicyRegistry.getByHandlerId(any()) } returns retryPolicy
    }

    @Test
    fun `handle schedules retry when retries not exhausted and exception is retryable`() {
        val record =
            outboxRecord(
                handlerId = "test-handler",
                failureCount = 2,
                failureException = RuntimeException("Retryable error"),
            )

        every { retryPolicy.maxRetries() } returns 5
        every { retryPolicy.shouldRetry(any()) } returns true
        every { retryPolicy.nextDelay(2) } returns Duration.ofMinutes(5)
        every { recordRepository.save(any() as OutboxRecord<*>) } returns record

        val result = processor.handle(record)

        assertThat(result).isFalse()
        assertThat(record.nextRetryAt).isEqualTo(Instant.now(clock).plus(5, ChronoUnit.MINUTES))

        verify { retryPolicyRegistry.getByHandlerId("test-handler") }
        verify { retryPolicy.shouldRetry(any()) }
        verify { retryPolicy.nextDelay(2) }
        verify { recordRepository.save(record) }
        verify(exactly = 0) { nextProcessor.handle(any()) }
    }

    @Test
    fun `handle passes to next processor when retries are exhausted`() {
        val record =
            outboxRecord(
                handlerId = "test-handler",
                failureCount = 6,
                failureException = RuntimeException("Error"),
            )

        every { retryPolicy.maxRetries() } returns 5
        every { retryPolicy.shouldRetry(any()) } returns true
        every { nextProcessor.handle(any()) } returns false

        val result = processor.handle(record)

        assertThat(result).isFalse()

        verify { nextProcessor.handle(record) }
        verify(exactly = 0) { recordRepository.save(any() as OutboxRecord<*>) }
    }

    @Test
    fun `handle passes to next processor when exception is non-retryable`() {
        val record =
            outboxRecord(
                handlerId = "test-handler",
                failureCount = 1,
                failureException = IllegalArgumentException("Non-retryable"),
            )

        every { retryPolicy.maxRetries() } returns 5
        every { retryPolicy.shouldRetry(any()) } returns false
        every { nextProcessor.handle(any()) } returns true

        val result = processor.handle(record)

        assertThat(result).isTrue()

        verify { nextProcessor.handle(record) }
        verify(exactly = 0) { recordRepository.save(any() as OutboxRecord<*>) }
    }

    @Test
    fun `handle returns false when no next processor and cannot retry`() {
        val record =
            outboxRecord(
                handlerId = "test-handler",
                failureCount = 6,
                failureException = RuntimeException("Error"),
            )
        val processorWithoutNext = RetryOutboxRecordProcessor(retryPolicyRegistry, recordRepository, clock)

        every { retryPolicy.maxRetries() } returns 5
        every { retryPolicy.shouldRetry(any()) } returns true

        val result = processorWithoutNext.handle(record)

        assertThat(result).isFalse()

        verify(exactly = 0) { recordRepository.save(any() as OutboxRecord<*>) }
        verify(exactly = 0) { retryPolicy.nextDelay(any()) }
    }

    @Test
    fun `handle schedules retry with correct delay calculation`() {
        val record =
            outboxRecord(
                handlerId = "test-handler",
                failureCount = 3,
                failureException = RuntimeException("Error"),
            )

        every { retryPolicy.maxRetries() } returns 10
        every { retryPolicy.shouldRetry(any()) } returns true
        every { retryPolicy.nextDelay(3) } returns Duration.ofSeconds(30)
        every { recordRepository.save(any() as OutboxRecord<*>) } returns record

        processor.handle(record)

        assertThat(record.nextRetryAt).isEqualTo(Instant.now(clock).plus(30, ChronoUnit.SECONDS))

        verify { retryPolicy.nextDelay(3) }
    }

    @Test
    fun `handle uses failure exception for shouldRetry check`() {
        val exception = IllegalStateException("Specific error")
        val record =
            outboxRecord(
                handlerId = "test-handler",
                failureCount = 1,
                failureException = exception,
            )

        every { retryPolicy.maxRetries() } returns 5
        every { retryPolicy.shouldRetry(exception) } returns true
        every { retryPolicy.nextDelay(1) } returns Duration.ofMinutes(1)
        every { recordRepository.save(any() as OutboxRecord<*>) } returns record

        processor.handle(record)

        verify { retryPolicy.shouldRetry(exception) }
    }

    @Test
    fun `handle throws exception when failureException is null`() {
        val record =
            outboxRecord(
                handlerId = "test-handler",
                failureCount = 1,
                failureException = null,
            )

        every { retryPolicy.maxRetries() } returns 5

        assertThat(
            org.junit.jupiter.api.assertThrows<IllegalStateException> {
                processor.handle(record)
            },
        ).hasMessageContaining("Expected failure exception in record")
    }

    @Test
    fun `handle schedules retry for first failure`() {
        val record =
            outboxRecord(
                handlerId = "test-handler",
                failureCount = 1,
                failureException = RuntimeException("First error"),
            )

        every { retryPolicy.maxRetries() } returns 3
        every { retryPolicy.shouldRetry(any()) } returns true
        every { retryPolicy.nextDelay(1) } returns Duration.ofMinutes(2)
        every { recordRepository.save(any() as OutboxRecord<*>) } returns record

        val result = processor.handle(record)

        assertThat(result).isFalse()
        assertThat(record.nextRetryAt).isEqualTo(Instant.now(clock).plus(2, ChronoUnit.MINUTES))
    }

    @Test
    fun `handle passes to next processor when both conditions fail`() {
        val record =
            outboxRecord(
                handlerId = "test-handler",
                failureCount = 11,
                failureException = IllegalArgumentException("Non-retryable"),
            )

        every { retryPolicy.maxRetries() } returns 10
        every { retryPolicy.shouldRetry(any()) } returns false
        every { nextProcessor.handle(any()) } returns false

        val result = processor.handle(record)

        assertThat(result).isFalse()

        verify { nextProcessor.handle(record) }
        verify(exactly = 0) { recordRepository.save(any() as OutboxRecord<*>) }
    }
}
