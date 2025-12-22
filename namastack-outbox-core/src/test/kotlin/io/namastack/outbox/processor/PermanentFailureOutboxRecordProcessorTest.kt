package io.namastack.outbox.processor

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.OutboxRecordStatus
import io.namastack.outbox.OutboxRecordTestFactory.outboxRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PermanentFailureOutboxRecordProcessorTest {
    private lateinit var recordRepository: OutboxRecordRepository
    private lateinit var processor: PermanentFailureOutboxRecordProcessor

    @BeforeEach
    fun setUp() {
        recordRepository = mockk()

        processor = PermanentFailureOutboxRecordProcessor(recordRepository)
    }

    @Test
    fun `handle marks record as FAILED and saves it`() {
        val record =
            outboxRecord(
                id = "record-1",
                recordKey = "order-123",
                handlerId = "orderHandler",
                failureCount = 6,
                failureException = RuntimeException("Final error"),
            )

        every { recordRepository.save(any() as OutboxRecord<*>) } returns record

        val result = processor.handle(record)

        assertThat(result).isFalse()
        assertThat(record.status).isEqualTo(OutboxRecordStatus.FAILED)

        verify { recordRepository.save(record) }
    }

    @Test
    fun `handle returns false when no next processor exists`() {
        val record =
            outboxRecord(
                handlerId = "test-handler",
                failureCount = 10,
                failureException = RuntimeException("Error"),
            )
        val processorWithoutNext = PermanentFailureOutboxRecordProcessor(recordRepository)

        every { recordRepository.save(any() as OutboxRecord<*>) } returns record

        val result = processorWithoutNext.handle(record)

        assertThat(result).isFalse()
        assertThat(record.status).isEqualTo(OutboxRecordStatus.FAILED)

        verify { recordRepository.save(record) }
    }

    @Test
    fun `handle marks record as FAILED with high failure count`() {
        val record =
            outboxRecord(
                handlerId = "test-handler",
                failureCount = 100,
                failureException = RuntimeException("Many failures"),
            )

        every { recordRepository.save(any() as OutboxRecord<*>) } returns record

        processor.handle(record)

        assertThat(record.status).isEqualTo(OutboxRecordStatus.FAILED)
        assertThat(record.failureCount).isEqualTo(100)

        verify { recordRepository.save(record) }
    }

    @Test
    fun `handle marks record as FAILED even if status was already FAILED`() {
        val record =
            outboxRecord(
                handlerId = "test-handler",
                failureCount = 5,
                failureException = RuntimeException("Error"),
                status = OutboxRecordStatus.FAILED,
            )

        every { recordRepository.save(any() as OutboxRecord<*>) } returns record

        processor.handle(record)

        assertThat(record.status).isEqualTo(OutboxRecordStatus.FAILED)

        verify { recordRepository.save(record) }
    }

    @Test
    fun `handle preserves failure exception`() {
        val exception = IllegalStateException("Permanent failure")
        val record =
            outboxRecord(
                handlerId = "test-handler",
                failureCount = 3,
                failureException = exception,
            )

        every { recordRepository.save(any() as OutboxRecord<*>) } returns record

        processor.handle(record)

        assertThat(record.failureException).isEqualTo(exception)

        verify { recordRepository.save(record) }
    }

    @Test
    fun `handle preserves record key and handlerId`() {
        val record =
            outboxRecord(
                recordKey = "important-key",
                handlerId = "criticalHandler",
                failureCount = 3,
                failureException = RuntimeException("Error"),
            )

        every { recordRepository.save(any() as OutboxRecord<*>) } returns record

        processor.handle(record)

        assertThat(record.key).isEqualTo("important-key")
        assertThat(record.handlerId).isEqualTo("criticalHandler")

        verify { recordRepository.save(record) }
    }
}
