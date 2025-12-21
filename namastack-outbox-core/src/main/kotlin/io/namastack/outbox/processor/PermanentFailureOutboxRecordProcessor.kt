package io.namastack.outbox.processor

import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository
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
 * @since 0.5.0
 */
class PermanentFailureOutboxRecordProcessor(
    private val recordRepository: OutboxRecordRepository,
) : OutboxRecordProcessor() {
    private val log = LoggerFactory.getLogger(PermanentFailureOutboxRecordProcessor::class.java)

    /**
     * Processes record by marking it as permanently FAILED.
     *
     * @return result from next processor in chain (typically false as this is usually the last processor)
     */
    override fun handle(record: OutboxRecord<*>): Boolean {
        record.markFailed()
        recordRepository.save(record)

        log.warn(
            "Record {} for key {} marked as FAILED permanently after {} failures",
            record.id,
            record.key,
            record.failureCount,
        )

        return handleNext(record)
    }
}
