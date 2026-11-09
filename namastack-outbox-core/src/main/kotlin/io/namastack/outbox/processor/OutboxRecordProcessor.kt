package io.namastack.outbox.processor

import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.instrumentation.OutboxRecordProcessingOutcome

/**
 * Base class for chain-of-responsibility pattern for processing outbox records.
 *
 * Processors form a chain where each processor can handle a record or pass it to the next processor.
 *
 * @property next Next processor in the chain, or null until one is configured
 * @author Roland Beisel
 * @since 1.0.0
 */
abstract class OutboxRecordProcessor(
    protected var next: OutboxRecordProcessor? = null,
) {
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
     * @param record Record to pass to the next processor
     * @return Outcome returned by the next processor.
     * @throws IllegalStateException if the chain has no next processor.
     * @throws Exception if downstream processing fails
     */
    protected fun handleNext(record: OutboxRecord<*>): OutboxRecordProcessingOutcome =
        next?.handle(record)
            ?: error("No next processor configured after ${javaClass.simpleName}")

    /**
     * Processes the record.
     *
     * @param record Record to handle or pass to the next processor
     * @return Final outcome produced by this processor or a downstream processor.
     * @throws Exception if processing fails
     */
    abstract fun handle(record: OutboxRecord<*>): OutboxRecordProcessingOutcome
}
