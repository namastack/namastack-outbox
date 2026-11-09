package io.namastack.outbox.processor

import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.completion.OutboxRecordCompleter
import io.namastack.outbox.handler.OutboxFallbackDisposition
import io.namastack.outbox.handler.invoker.OutboxFallbackHandlerInvoker
import io.namastack.outbox.handler.registry.OutboxFallbackHandlerRegistry
import io.namastack.outbox.instrumentation.OutboxRecordProcessingOutcome
import org.slf4j.LoggerFactory

/**
 * Processor that handles permanently failed records by invoking fallback handlers.
 *
 * If the fallback succeeds with [OutboxFallbackDisposition.COMPLETE], marks the record as
 * COMPLETED or deletes it. With [OutboxFallbackDisposition.FAIL], retains the original failure
 * and passes the record to the next processor, which marks it as FAILED. If the fallback throws,
 * stores its exception and passes the record to the next processor.
 *
 * @param fallbackHandlerRegistry Registry of fallback handlers
 * @param fallbackHandlerInvoker Invoker for fallback handlers
 * @param completer Completes successfully processed records
 *
 * @author Roland Beisel
 * @since 1.0.0
 */
class FallbackOutboxRecordProcessor(
    private val fallbackHandlerRegistry: OutboxFallbackHandlerRegistry,
    private val fallbackHandlerInvoker: OutboxFallbackHandlerInvoker,
    private val completer: OutboxRecordCompleter,
) : OutboxRecordProcessor() {
    private val log = LoggerFactory.getLogger(FallbackOutboxRecordProcessor::class.java)

    /**
     * Processes record by dispatching to fallback handler.
     *
     * If no fallback handler is registered, delegates to the next processor in the chain.
     * If the fallback handler throws, stores the exception on the record and delegates to the next processor.
     * If the fallback handler succeeds with [OutboxFallbackDisposition.FAIL], delegates to the next
     * processor without replacing the original failure.
     *
     * @param record Record whose primary handler failed
     * @return [OutboxRecordProcessingOutcome.COMPLETED] if the fallback succeeds with
     * [OutboxFallbackDisposition.COMPLETE], otherwise the outcome returned by the downstream chain.
     * @throws Exception if completion persistence or downstream processing fails
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
                completer.complete(record)
                OutboxRecordProcessingOutcome.COMPLETED
            }

            OutboxFallbackDisposition.FAIL -> {
                log.debug("Fallback handler succeeded for record {}, keeping original failure", record.id)
                handleNext(record)
            }
        }
    }
}
