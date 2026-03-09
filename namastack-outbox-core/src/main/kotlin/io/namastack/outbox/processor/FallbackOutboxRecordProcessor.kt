package io.namastack.outbox.processor

import io.namastack.outbox.OutboxProperties
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.handler.invoker.OutboxFallbackHandlerInvoker
import io.namastack.outbox.handler.registry.OutboxFallbackHandlerRegistry
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
    private val fallbackHandlerRegistry: OutboxFallbackHandlerRegistry,
    private val fallbackHandlerInvoker: OutboxFallbackHandlerInvoker,
    private val properties: OutboxProperties,
    private val clock: Clock,
) : OutboxRecordProcessor() {
    private val log = LoggerFactory.getLogger(FallbackOutboxRecordProcessor::class.java)

    /**
     * Processes record by dispatching to fallback handler.
     *
     * If no fallback handler is registered, delegates to the next processor in the chain.
     * If the fallback handler throws, stores the exception on the record and delegates to the next processor.
     *
     * @return true if the fallback handler succeeded, otherwise the result of the next processor in the chain
     */
    override fun handle(record: OutboxRecord<*>): Boolean {
        try {
            if (!fallbackHandlerRegistry.existsByHandlerId(record.handlerId)) {
                log.debug("No fallback handler registered for handlerId: {}", record.handlerId)
                return handleNext(record)
            }
            log.debug("Dispatching record {} to fallback handler", record.id)
            fallbackHandlerInvoker.dispatch(record)

            completeRecord(record, recordRepository, properties, clock)

            return true
        } catch (ex: Exception) {
            log.error("Fallback handler failed for record {}: {}", record.id, ex.message, ex)

            record.updateFailureException(ex)

            return handleNext(record)
        }
    }
}
