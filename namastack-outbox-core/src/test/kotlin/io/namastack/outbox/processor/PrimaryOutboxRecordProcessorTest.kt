package io.namastack.outbox.processor

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.OutboxProperties
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.OutboxRecordStatus
import io.namastack.outbox.handler.invoker.OutboxHandlerInvoker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class PrimaryOutboxRecordProcessorTest {
    private lateinit var handlerInvoker: OutboxHandlerInvoker
    private lateinit var recordRepository: OutboxRecordRepository
    private lateinit var properties: OutboxProperties
    private lateinit var clock: Clock
    private lateinit var processor: PrimaryOutboxRecordProcessor
    private lateinit var nextProcessor: OutboxRecordProcessor

    @BeforeEach
    fun setUp() {
        handlerInvoker = mockk()
        recordRepository = mockk()
        properties = OutboxProperties()
        clock = Clock.fixed(Instant.parse("2024-01-01T10:00:00Z"), ZoneOffset.UTC)
        nextProcessor = mockk()

        processor = PrimaryOutboxRecordProcessor(handlerInvoker, recordRepository, properties, clock)
        processor.setNext(nextProcessor)
    }

    @Test
    fun `handle completes and saves the record when dispatch succeeds and completed records are retained`() {
        val record = createRecord()
        properties.processing.deleteCompletedRecords = false

        justRun { handlerInvoker.dispatch(any()) }
        every { recordRepository.save(any() as OutboxRecord<*>) } returns record

        val result = processor.handle(record)

        assertThat(result).isTrue()
        assertThat(record.status).isEqualTo(OutboxRecordStatus.COMPLETED)
        assertThat(record.completedAt).isEqualTo(Instant.now(clock))

        verify { handlerInvoker.dispatch(record) }
        verify { recordRepository.save(record) }
        verify(exactly = 0) { recordRepository.deleteById(any()) }
        verify(exactly = 0) { nextProcessor.handle(any()) }
    }

    @Test
    fun `handle deletes the record when dispatch succeeds and completed records are configured for deletion`() {
        val record = createRecord()
        properties.processing.deleteCompletedRecords = true

        justRun { handlerInvoker.dispatch(any()) }
        justRun { recordRepository.deleteById(any()) }

        val result = processor.handle(record)

        assertThat(result).isTrue()

        verify { handlerInvoker.dispatch(record) }
        verify { recordRepository.deleteById(record.id) }
        verify(exactly = 0) { recordRepository.save(any() as OutboxRecord<*>) }
        verify(exactly = 0) { nextProcessor.handle(any()) }
    }

    @Test
    fun `handle updates failure metadata before delegating to the next processor when dispatch throws`() {
        val record = createRecord()
        val exception = RuntimeException("Handler failed")

        every { handlerInvoker.dispatch(any()) } throws exception
        every { nextProcessor.handle(any()) } returns false

        val result = processor.handle(record)

        assertThat(result).isFalse()
        assertThat(record.failureCount).isEqualTo(1)
        assertThat(record.failureException).isEqualTo(exception)
        assertThat(record.failureReason).isEqualTo("Handler failed")

        verify { handlerInvoker.dispatch(record) }
        verify { nextProcessor.handle(record) }
        verify(exactly = 0) { recordRepository.save(any() as OutboxRecord<*>) }
        verify(exactly = 0) { recordRepository.deleteById(any()) }
    }

    @Test
    fun `handle returns the next processor result when dispatch fails`() {
        val record = createRecord()
        val exception = IllegalStateException("Processing error")

        every { handlerInvoker.dispatch(any()) } throws exception
        every { nextProcessor.handle(any()) } returns true

        val result = processor.handle(record)

        assertThat(result).isTrue()
        assertThat(record.failureCount).isEqualTo(1)

        verify { nextProcessor.handle(record) }
    }

    @Test
    fun `handle returns false when dispatch fails and no next processor exists`() {
        val record = createRecord()
        val exception = RuntimeException("Handler error")
        val processorWithoutNext = PrimaryOutboxRecordProcessor(handlerInvoker, recordRepository, properties, clock)

        every { handlerInvoker.dispatch(any()) } throws exception

        val result = processorWithoutNext.handle(record)

        assertThat(result).isFalse()
        assertThat(record.failureCount).isEqualTo(1)
        assertThat(record.failureException).isEqualTo(exception)
    }

    @Test
    fun `handle keeps failure reason null when dispatch exception has no message`() {
        val record = createRecord()
        val exception = RuntimeException()

        every { handlerInvoker.dispatch(any()) } throws exception
        every { nextProcessor.handle(any()) } returns false

        processor.handle(record)

        assertThat(record.failureException).isEqualTo(exception)
        assertThat(record.failureReason).isNull()
    }

    private fun createRecord(
        id: String = "record-1",
        key: String = "order-123",
        handlerId: String = "orderHandler",
        payload: Any? = mapOf("orderId" to "123"),
        createdAt: Instant = Instant.now(clock),
    ): OutboxRecord<Any?> =
        OutboxRecord.restore(
            id = id,
            recordKey = key,
            payload = payload,
            context = emptyMap(),
            createdAt = createdAt,
            status = OutboxRecordStatus.NEW,
            completedAt = null,
            failureCount = 0,
            failureException = null,
            failureReason = null,
            partition = 1,
            nextRetryAt = createdAt,
            handlerId = handlerId,
        )
}
