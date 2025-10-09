package io.namastack.springoutbox

/**
 * Functional interface for processing outbox records.
 *
 * Implementations of this interface define how outbox records should be processed,
 * typically by publishing events to message brokers or external systems.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
fun interface OutboxRecordProcessor {
    /**
     * Processes an outbox record.
     *
     * This method is called for each outbox record that needs to be processed.
     * Implementations should handle the actual publishing or processing logic.
     *
     * @param record The outbox record to process
     */
    fun process(record: OutboxRecord)
}
