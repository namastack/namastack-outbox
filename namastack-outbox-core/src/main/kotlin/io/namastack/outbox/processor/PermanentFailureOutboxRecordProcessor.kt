package io.namastack.outbox.processor

import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.instrumentation.OutboxRecordProcessingOutcome
import org.slf4j.LoggerFactory

/**
 * Processor that marks records as permanently FAILED when processing cannot continue.
 *
 * This processor is invoked when all retry attempts are exhausted and no fallback
 * handler is available or the fallback handler has also failed.
 *
 * @param recordRepository Repository for persisting failed state
 *
 * @author Roland Beisel
 * @since 1.0.0
 */
class PermanentFailureOutboxRecordProcessor(
    private val recordRepository: OutboxRecordRepository,
) : OutboxRecordProcessor() {
    private val log = LoggerFactory.getLogger(PermanentFailureOutboxRecordProcessor::class.java)

    /**
     * Processes record by marking it as permanently FAILED.
     *
     * @return [OutboxRecordProcessingOutcome.FAILED].
     */
    override fun handle(record: OutboxRecord<*>): OutboxRecordProcessingOutcome {
        record.markFailed()
        recordRepository.updateProcessingState(record)

        log.warn(
            "Record {} (businessKey={}, processingGroup={}, storageKey={}) marked as FAILED permanently after {} failures",
            record.id,
            record.businessKey,
            record.processingGroup,
            record.key,
            record.failureCount,
        )

        return OutboxRecordProcessingOutcome.FAILED
    }
}
