package io.namastack.outbox.processor

import io.namastack.outbox.OutboxProperties
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.handler.invoker.OutboxFallbackHandlerInvoker
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * Processor that handles permanently failed records by invoking fallback handlers.
 *
 * On success: marks record as COMPLETED or deletes it.
 * On failure: stores fallback exception, passes to next processor in chain.
 *
 * @param recordRepository Repository for persisting record state
 * @param fallbackHandlerInvoker Invoker for fallback handlers
 * @param properties Configuration
 * @param clock Clock for completion timestamp
 *
 * @author Roland Beisel
 * @since 1.0.0
 */
class FallbackOutboxRecordProcessor(
    private val recordRepository: OutboxRecordRepository,
    private val fallbackHandlerInvoker: OutboxFallbackHandlerInvoker,
    private val properties: OutboxProperties,
    private val clock: Clock,
) : OutboxRecordProcessor() {
    private val log = LoggerFactory.getLogger(FallbackOutboxRecordProcessor::class.java)

    /**
     * Processes record by dispatching to fallback handler.
     *
     * @return true if the fallback handler succeeded, false if no fallback handler was
     *   registered or the fallback failed and no further processor in the chain handled the record
     */
    override fun handle(record: OutboxRecord<*>): Boolean {
        try {
            log.debug("Dispatching record {} to fallback handler", record.id)
            val success = fallbackHandlerInvoker.dispatch(record)

            if (!success) return handleNext(record)

            completeRecord(record, recordRepository, properties, clock)

            return true
        } catch (ex: Exception) {
            log.error("Fallback handler failed for record {}: {}", record.id, ex.message, ex)

            record.updateFailureException(ex)

            return handleNext(record)
        }
    }
}
