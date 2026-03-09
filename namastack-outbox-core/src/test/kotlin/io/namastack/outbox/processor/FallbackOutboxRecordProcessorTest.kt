package io.namastack.outbox.processor

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.OutboxProperties
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.OutboxRecordStatus
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
    fun `handle completes and saves the record when fallback dispatch succeeds`() {
        val record = createFailedRecord()
        properties.processing.deleteCompletedRecords = false

        every { fallbackHandlerInvoker.dispatch(any()) } returns true
        every { recordRepository.save(any() as OutboxRecord<*>) } returns record

        val result = processor.handle(record)

        assertThat(result).isTrue()
        assertThat(record.status).isEqualTo(OutboxRecordStatus.COMPLETED)
        assertThat(record.completedAt).isEqualTo(Instant.now(clock))
        assertThat(record.failureCount).isEqualTo(4)
        assertThat(record.failureException).hasMessage("Handler failed")

        verify { fallbackHandlerInvoker.dispatch(record) }
        verify { recordRepository.save(record) }
        verify(exactly = 0) { recordRepository.deleteById(any()) }
        verify(exactly = 0) { nextProcessor.handle(any()) }
    }

    @Test
    fun `handle deletes the record when fallback dispatch succeeds`() {
        val record = createFailedRecord()
        properties.processing.deleteCompletedRecords = true

        every { fallbackHandlerInvoker.dispatch(any()) } returns true
        justRun { recordRepository.deleteById(any()) }

        val result = processor.handle(record)

        assertThat(result).isTrue()
        assertThat(record.status).isEqualTo(OutboxRecordStatus.FAILED)
        assertThat(record.completedAt).isNull()

        verify { fallbackHandlerInvoker.dispatch(record) }
        verify { recordRepository.deleteById(record.id) }
        verify(exactly = 0) { recordRepository.save(any() as OutboxRecord<*>) }
        verify(exactly = 0) { nextProcessor.handle(any()) }
    }

    @Test
    fun `handle delegates to the next processor without mutating the record when fallback dispatch returns false`() {
        val record = createFailedRecord()

        every { fallbackHandlerInvoker.dispatch(any()) } returns false
        every { nextProcessor.handle(any()) } returns false

        val result = processor.handle(record)

        assertThat(result).isFalse()
        assertThat(record.status).isEqualTo(OutboxRecordStatus.FAILED)
        assertThat(record.completedAt).isNull()
        assertThat(record.failureCount).isEqualTo(4)
        assertThat(record.failureException).hasMessage("Handler failed")
        assertThat(record.failureReason).isEqualTo("Existing failure")

        verify { fallbackHandlerInvoker.dispatch(record) }
        verify { nextProcessor.handle(record) }
        verify(exactly = 0) { recordRepository.save(any() as OutboxRecord<*>) }
        verify(exactly = 0) { recordRepository.deleteById(any()) }
    }

    @Test
    fun `handle stores the fallback exception before delegating when fallback dispatch throws`() {
        val record = createFailedRecord()
        val fallbackException = IllegalStateException("Fallback failed")

        every { fallbackHandlerInvoker.dispatch(any()) } throws fallbackException
        every { nextProcessor.handle(any()) } returns false

        val result = processor.handle(record)

        assertThat(result).isFalse()
        assertThat(record.status).isEqualTo(OutboxRecordStatus.FAILED)
        assertThat(record.completedAt).isNull()
        assertThat(record.failureCount).isEqualTo(4)
        assertThat(record.failureException).isEqualTo(fallbackException)
        assertThat(record.failureReason).isEqualTo("Fallback failed")

        verify { fallbackHandlerInvoker.dispatch(record) }
        verify { nextProcessor.handle(record) }
        verify(exactly = 0) { recordRepository.save(any() as OutboxRecord<*>) }
        verify(exactly = 0) { recordRepository.deleteById(any()) }
    }

    @Test
    fun `handle keeps failure reason null when fallback dispatch throws an exception without a message`() {
        val record = createFailedRecord(failureReason = "Existing failure")
        val fallbackException = IllegalStateException()

        every { fallbackHandlerInvoker.dispatch(any()) } throws fallbackException
        every { nextProcessor.handle(any()) } returns false

        processor.handle(record)

        assertThat(record.failureException).isEqualTo(fallbackException)
        assertThat(record.failureReason).isNull()
    }

    @Test
    fun `handle returns false when fallback dispatch returns false and no next processor exists`() {
        val record = createFailedRecord()
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
        assertThat(record.status).isEqualTo(OutboxRecordStatus.FAILED)
        assertThat(record.completedAt).isNull()
        verify(exactly = 0) { recordRepository.save(any() as OutboxRecord<*>) }
        verify(exactly = 0) { recordRepository.deleteById(any()) }
    }

    @Test
    fun `handle returns the next processor result when fallback dispatch returns false`() {
        val record = createFailedRecord()

        every { fallbackHandlerInvoker.dispatch(any()) } returns false
        every { nextProcessor.handle(any()) } returns true

        val result = processor.handle(record)

        assertThat(result).isTrue()

        verify { fallbackHandlerInvoker.dispatch(record) }
        verify { nextProcessor.handle(record) }
    }

    private fun createFailedRecord(
        id: String = "record-1",
        key: String = "order-123",
        handlerId: String = "orderHandler",
        payload: Any? = mapOf("orderId" to "123"),
        createdAt: Instant = Instant.now(clock),
        failureCount: Int = 4,
        failureException: Throwable? = RuntimeException("Handler failed"),
        failureReason: String? = "Existing failure",
    ): OutboxRecord<Any?> =
        OutboxRecord.restore(
            id = id,
            recordKey = key,
            payload = payload,
            context = emptyMap(),
            createdAt = createdAt,
            status = OutboxRecordStatus.FAILED,
            completedAt = null,
            failureCount = failureCount,
            failureException = failureException,
            failureReason = failureReason,
            partition = 1,
            nextRetryAt = createdAt,
            handlerId = handlerId,
        )
}
