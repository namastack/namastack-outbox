package io.namastack.outbox.processor

import io.namastack.outbox.OutboxProperties
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.handler.invoker.OutboxHandlerInvoker
import io.namastack.outbox.instrumentation.OutboxRecordProcessingOutcome
import io.namastack.outbox.runtime.OutboxRuntimeSettings
import io.namastack.outbox.runtime.toRuntimeSettings
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * Primary processor that dispatches records to their handlers.
 *
 * On success: marks record as COMPLETED or deletes it.
 * On failure: increments failure count, stores exception, passes to next processor in chain.
 *
 * @param handlerInvoker Invoker for handlers
 * @param recordRepository Repository for persisting record state
 * @param settingsProvider Effective processing settings
 * @param clock Clock for completion timestamp
 *
 * @author Roland Beisel
 * @since 1.0.0
 */
class PrimaryOutboxRecordProcessor private constructor(
    private val handlerInvoker: OutboxHandlerInvoker,
    private val recordRepository: OutboxRecordRepository,
    private val settingsProvider: () -> OutboxRuntimeSettings.Processing,
    private val clock: Clock,
) : OutboxRecordProcessor() {
    /** Creates a processor from effective runtime settings. */
    constructor(
        handlerInvoker: OutboxHandlerInvoker,
        recordRepository: OutboxRecordRepository,
        settings: OutboxRuntimeSettings.Processing,
        clock: Clock,
    ) : this(handlerInvoker, recordRepository, { settings }, clock)

    /** Creates a processor from Spring-bound properties. */
    constructor(
        handlerInvoker: OutboxHandlerInvoker,
        recordRepository: OutboxRecordRepository,
        properties: OutboxProperties,
        clock: Clock,
    ) : this(handlerInvoker, recordRepository, { properties.toRuntimeSettings().processing }, clock)

    private val log = LoggerFactory.getLogger(PrimaryOutboxRecordProcessor::class.java)

    /**
     * Processes record by dispatching to its handler.
     *
     * @return [OutboxRecordProcessingOutcome.COMPLETED] if the handler succeeds, otherwise the
     * outcome returned by the downstream chain.
     */
    override fun handle(record: OutboxRecord<*>): OutboxRecordProcessingOutcome {
        if (record.payload != null) {
            handlerInvoker.ensureHandlerAvailable(record)
        }

        try {
            log.trace("Dispatching record {} to handler {}", record.id, record.handlerId)
            handlerInvoker.dispatch(record)

            completeRecord(record, recordRepository, settingsProvider(), clock)

            return OutboxRecordProcessingOutcome.COMPLETED
        } catch (ex: Exception) {
            log.debug("Handler failed for record {} (key: {}): {}", record.id, record.key, ex.message)

            record.incrementFailureCount()
            record.updateFailureException(ex)

            return handleNext(record)
        }
    }
}
