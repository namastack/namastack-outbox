package io.namastack.outbox.processor

import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.handler.OutboxFallbackDisposition
import io.namastack.outbox.handler.invoker.OutboxFallbackHandlerInvoker
import io.namastack.outbox.handler.registry.OutboxFallbackHandlerRegistry
import io.namastack.outbox.instrumentation.OutboxRecordProcessingOutcome
import io.namastack.outbox.runtime.OutboxRuntimeSettings
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * Processor that handles permanently failed records by invoking fallback handlers.
 *
 * On success with [OutboxFallbackDisposition.COMPLETE]: marks record as COMPLETED or deletes it.
 * On success with [OutboxFallbackDisposition.KEEP_FAILED]: keeps the original failure and passes
 * the record to the next processor in chain, which marks it as FAILED.
 * On failure: stores fallback exception, passes to next processor in chain.
 *
 * @param recordRepository Repository for persisting record state
 * @param fallbackHandlerInvoker Invoker for fallback handlers
 * @param settings Effective processing settings
 * @param clock Clock for completion timestamp
 *
 * @author Roland Beisel
 * @since 1.0.0
 */
class FallbackOutboxRecordProcessor(
    private val recordRepository: OutboxRecordRepository,
    private val fallbackHandlerRegistry: OutboxFallbackHandlerRegistry,
    private val fallbackHandlerInvoker: OutboxFallbackHandlerInvoker,
    private val settings: OutboxRuntimeSettings.Processing,
    private val clock: Clock,
) : OutboxRecordProcessor() {
    private val log = LoggerFactory.getLogger(FallbackOutboxRecordProcessor::class.java)

    /**
     * Processes record by dispatching to fallback handler.
     *
     * If no fallback handler is registered, delegates to the next processor in the chain.
     * If the fallback handler throws, stores the exception on the record and delegates to the next processor.
     * If the fallback handler succeeds with [OutboxFallbackDisposition.KEEP_FAILED], delegates to the next
     * processor without replacing the original failure.
     *
     * @return [OutboxRecordProcessingOutcome.COMPLETED] if the fallback succeeds with
     * [OutboxFallbackDisposition.COMPLETE], otherwise the outcome returned by the downstream chain.
     */
    override fun handle(record: OutboxRecord<*>): OutboxRecordProcessingOutcome {
        val fallback = fallbackHandlerRegistry.getByHandlerId(record.handlerId)
        if (fallback == null) {
            log.debug("No fallback handler registered for handlerId: {}", record.handlerId)
            return handleNext(record)
        }

        try {
            log.debug("Dispatching record {} to fallback handler", record.id)
            fallbackHandlerInvoker.dispatch(record)
        } catch (ex: Exception) {
            log.error("Fallback handler failed for record {}: {}", record.id, ex.message, ex)

            record.updateFailureException(ex)

            return handleNext(record)
        }

        return when (fallback.disposition) {
            OutboxFallbackDisposition.COMPLETE -> {
                completeRecord(record, recordRepository, settings, clock)
                OutboxRecordProcessingOutcome.COMPLETED
            }

            OutboxFallbackDisposition.KEEP_FAILED -> {
                log.debug("Fallback handler succeeded for record {}, keeping original failure", record.id)
                handleNext(record)
            }
        }
    }
}
