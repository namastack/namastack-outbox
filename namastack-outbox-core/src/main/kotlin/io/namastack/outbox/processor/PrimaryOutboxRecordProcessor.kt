package io.namastack.outbox.processor

import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.completion.OutboxRecordCompleter
import io.namastack.outbox.handler.invoker.OutboxHandlerInvoker
import io.namastack.outbox.instrumentation.OutboxRecordProcessingOutcome
import org.slf4j.LoggerFactory

/**
 * Primary processor that dispatches records to their handlers.
 *
 * On successful dispatch: marks the record as COMPLETED or deletes it.
 * If dispatch throws: increments the failure count, stores the exception, and passes the record
 * to the next processor in the chain.
 * Completion persistence failures propagate without entering handler retry or fallback processing.
 *
 * @param handlerInvoker Invoker for handlers
 * @param completer Completes successfully processed records
 *
 * @author Roland Beisel
 * @since 1.0.0
 */
class PrimaryOutboxRecordProcessor(
    private val handlerInvoker: OutboxHandlerInvoker,
    private val completer: OutboxRecordCompleter,
) : OutboxRecordProcessor() {
    private val log = LoggerFactory.getLogger(PrimaryOutboxRecordProcessor::class.java)

    /**
     * Processes record by dispatching to its handler.
     *
     * @param record Record to process
     * @return [OutboxRecordProcessingOutcome.COMPLETED] if the handler and completion succeed, otherwise the
     * outcome returned by the downstream chain.
     * @throws Exception if handler lookup, completion persistence, or downstream processing fails
     */
    override fun handle(record: OutboxRecord<*>): OutboxRecordProcessingOutcome {
        if (record.payload != null) {
            handlerInvoker.ensureHandlerAvailable(record)
        }

        try {
            log.trace("Dispatching record {} to handler {}", record.id, record.handlerId)
            handlerInvoker.dispatch(record)
        } catch (ex: Exception) {
            log.debug(
                "Handler failed for record {} (businessKey={}, processingGroup={}, storageKey={}): {}",
                record.id,
                record.businessKey,
                record.processingGroup,
                record.key,
                ex.message,
            )

            record.incrementFailureCount()
            record.updateFailureException(ex)

            return handleNext(record)
        }

        completer.complete(record)

        return OutboxRecordProcessingOutcome.COMPLETED
    }
}
