package io.namastack.outbox.processor

import io.namastack.outbox.OutboxProperties
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * Base class for chain-of-responsibility pattern for processing outbox records.
 *
 * Processors form a chain where each processor can handle a record or pass it to the next processor.
 *
 * @author Roland Beisel
 * @since 1.0.0
 */
abstract class OutboxRecordProcessor(
    protected var next: OutboxRecordProcessor? = null,
) {
    private val log = LoggerFactory.getLogger(OutboxRecordProcessor::class.java)

    /**
     * Sets the next processor in the chain.
     *
     * @param nextProcessor The next processor
     * @return The next processor for chaining
     */
    fun setNext(nextProcessor: OutboxRecordProcessor): OutboxRecordProcessor {
        this.next = nextProcessor

        return nextProcessor
    }

    /**
     * Passes record to next processor in chain.
     *
     * @return Result from next processor, or false if no next processor exists
     */
    fun handleNext(record: OutboxRecord<*>): Boolean = next?.handle(record) ?: false

    /**
     * Processes the record.
     *
     * @return true if record was successfully processed, false otherwise
     */
    abstract fun handle(record: OutboxRecord<*>): Boolean

    /**
     * Completes record after successful processing.
     * Deletes if configured, otherwise marks as COMPLETED.
     */
    protected fun completeRecord(
        record: OutboxRecord<*>,
        repository: OutboxRecordRepository,
        properties: OutboxProperties,
        clock: Clock,
    ) {
        if (properties.processing.deleteCompletedRecords) {
            log.trace("Deleting completed record {}", record.id)
            repository.deleteById(record.id)
        } else {
            log.trace("Marking record {} as COMPLETED", record.id)
            record.markCompleted(clock)
            repository.save(record)
        }
    }
}
