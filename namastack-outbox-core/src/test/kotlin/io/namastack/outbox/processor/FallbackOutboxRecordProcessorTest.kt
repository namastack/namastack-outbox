package io.namastack.outbox.processor

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.OutboxProperties
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.OutboxRecordStatus
import io.namastack.outbox.OutboxRecordTestFactory.outboxRecord
import io.namastack.outbox.handler.invoker.OutboxFallbackHandlerInvoker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class FallbackOutboxRecordProcessorTest {
    private lateinit var recordRepository: OutboxRecordRepository
    private lateinit var fallbackHandlerInvoker: OutboxFallbackHandlerInvoker
    private lateinit var properties: OutboxProperties
    private lateinit var clock: Clock
    private lateinit var processor: FallbackOutboxRecordProcessor
    private lateinit var nextProcessor: OutboxRecordProcessor

    @BeforeEach
    fun setUp() {
        recordRepository = mockk()
        fallbackHandlerInvoker = mockk()
        properties = OutboxProperties()
        clock = Clock.fixed(Instant.parse("2024-01-01T10:00:00Z"), ZoneOffset.UTC)
        nextProcessor = mockk()

        processor =
            FallbackOutboxRecordProcessor(
                recordRepository,
                fallbackHandlerInvoker,
                properties,
                clock,
            )
        processor.setNext(nextProcessor)
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

        every { fallbackHandlerInvoker.dispatch(any()) } returns true
        every { recordRepository.save(any() as OutboxRecord<*>) } returns record

        val result = processor.handle(record)

        assertThat(result).isTrue()
        assertThat(record.status).isEqualTo(OutboxRecordStatus.COMPLETED)
        assertThat(record.completedAt).isEqualTo(Instant.now(clock))

        verify { fallbackHandlerInvoker.dispatch(record) }
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

        every { fallbackHandlerInvoker.dispatch(any()) } returns true
        justRun { recordRepository.deleteById(any()) }

        val result = processor.handle(record)

        assertThat(result).isTrue()

        verify { fallbackHandlerInvoker.dispatch(record) }
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

        every { fallbackHandlerInvoker.dispatch(any()) } returns false
        every { nextProcessor.handle(any()) } returns false

        val result = processor.handle(record)

        assertThat(result).isFalse()

        verify { fallbackHandlerInvoker.dispatch(record) }
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

        every { fallbackHandlerInvoker.dispatch(any()) } throws fallbackException
        every { nextProcessor.handle(any()) } returns false

        val result = processor.handle(record)

        assertThat(result).isFalse()
        assertThat(record.failureException).isEqualTo(fallbackException)
        assertThat(record.failureReason).isEqualTo("Fallback failed")

        verify { fallbackHandlerInvoker.dispatch(record) }
        verify { nextProcessor.handle(record) }
        verify(exactly = 0) { recordRepository.save(any() as OutboxRecord<*>) }
        verify(exactly = 0) { recordRepository.deleteById(any()) }
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
                properties,
                clock,
            )

        every { fallbackHandlerInvoker.dispatch(any()) } returns false

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

        every { fallbackHandlerInvoker.dispatch(any()) } returns false
        every { nextProcessor.handle(any()) } returns true

        val result = processor.handle(record)

        assertThat(result).isTrue()

        verify { nextProcessor.handle(record) }
    }
}
