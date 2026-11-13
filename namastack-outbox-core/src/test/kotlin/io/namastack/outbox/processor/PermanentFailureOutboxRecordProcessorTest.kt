package io.namastack.outbox.processor

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.OutboxRecordStatus
import io.namastack.outbox.OutboxRecordTestFactory.outboxRecord
import io.namastack.outbox.completion.OutboxCompletionPersistence
import io.namastack.outbox.instrumentation.OutboxRecordProcessingOutcome
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PermanentFailureOutboxRecordProcessorTest {
    private lateinit var persistence: OutboxCompletionPersistence
    private lateinit var processor: PermanentFailureOutboxRecordProcessor

    @BeforeEach
    fun setUp() {
        persistence = mockk()

        processor = PermanentFailureOutboxRecordProcessor(persistence)
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

        every { persistence.updateProcessingState(any()) } returns Unit

        val result = processor.handle(record)

        assertThat(result).isEqualTo(OutboxRecordProcessingOutcome.FAILED)
        assertThat(record.status).isEqualTo(OutboxRecordStatus.FAILED)

        verify { persistence.updateProcessingState(record) }
    }

    @Test
    fun `handle returns failed when no next processor exists`() {
        val record =
            outboxRecord(
                handlerId = "test-handler",
                failureCount = 10,
                failureException = RuntimeException("Error"),
            )
        val processorWithoutNext = PermanentFailureOutboxRecordProcessor(persistence)

        every { persistence.updateProcessingState(any()) } returns Unit

        val result = processorWithoutNext.handle(record)

        assertThat(result).isEqualTo(OutboxRecordProcessingOutcome.FAILED)
        assertThat(record.status).isEqualTo(OutboxRecordStatus.FAILED)

        verify { persistence.updateProcessingState(record) }
    }

    @Test
    fun `handle marks record as FAILED with high failure count`() {
        val record =
            outboxRecord(
                handlerId = "test-handler",
                failureCount = 100,
                failureException = RuntimeException("Many failures"),
            )

        every { persistence.updateProcessingState(any()) } returns Unit

        processor.handle(record)

        assertThat(record.status).isEqualTo(OutboxRecordStatus.FAILED)
        assertThat(record.failureCount).isEqualTo(100)

        verify { persistence.updateProcessingState(record) }
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

        every { persistence.updateProcessingState(any()) } returns Unit

        processor.handle(record)

        assertThat(record.status).isEqualTo(OutboxRecordStatus.FAILED)

        verify { persistence.updateProcessingState(record) }
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

        every { persistence.updateProcessingState(any()) } returns Unit

        processor.handle(record)

        assertThat(record.failureException).isEqualTo(exception)

        verify { persistence.updateProcessingState(record) }
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

        every { persistence.updateProcessingState(any()) } returns Unit

        processor.handle(record)

        assertThat(record.key).isEqualTo("important-key")
        assertThat(record.handlerId).isEqualTo("criticalHandler")

        verify { persistence.updateProcessingState(record) }
    }

    @Test
    fun `persistence failure propagates without reporting a processing outcome`() {
        val record = outboxRecord(failureCount = 1, failureException = RuntimeException("Handler failed"))
        val failure = IllegalStateException("Write failed")
        every { persistence.updateProcessingState(record) } throws failure

        assertThatThrownBy { processor.handle(record) }.isSameAs(failure)
    }
}
