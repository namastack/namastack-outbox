package io.namastack.outbox.processor

import io.namastack.outbox.OutboxProperties
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.handler.invoker.OutboxHandlerInvoker
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * Primary processor that dispatches records to their handlers.
 *
 * On success: marks record as COMPLETED or deletes it.
 * On failure: increments failure count, stores exception, passes to next processor in chain.
 *
 * @param handlerInvoker Dispatcher for handlers
 * @param recordRepository Repository for persisting record state
 * @param properties Configuration
 * @param clock Clock for completion timestamp
 *
 * @author Roland Beisel
 * @since 0.5.0
 */
class PrimaryOutboxRecordProcessor(
    private val handlerInvoker: OutboxHandlerInvoker,
    private val recordRepository: OutboxRecordRepository,
    private val properties: OutboxProperties,
    private val clock: Clock,
) : OutboxRecordProcessor() {
    private val log = LoggerFactory.getLogger(PrimaryOutboxRecordProcessor::class.java)

    /**
     * Processes record by dispatching to its handler.
     *
     * @return true if handler succeeded, false if next processor should process
     */
    override fun handle(record: OutboxRecord<*>): Boolean {
        try {
            log.trace("Dispatching record {} to handler {}", record.id, record.handlerId)
            handlerInvoker.dispatch(record.payload, record.toMetadata())

            completeRecord(record, recordRepository, properties, clock)

            return true
        } catch (ex: Exception) {
            log.debug("Handler failed for record {} (key: {}): {}", record.id, record.key, ex.message)

            record.incrementFailureCount()
            record.updateFailureException(ex)

            return handleNext(record)
        }
    }
}
