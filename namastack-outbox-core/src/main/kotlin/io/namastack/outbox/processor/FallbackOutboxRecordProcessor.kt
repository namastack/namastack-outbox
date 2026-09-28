package io.namastack.outbox.processor

import io.namastack.outbox.OutboxProperties
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.handler.invoker.OutboxFallbackHandlerInvoker
import io.namastack.outbox.handler.registry.OutboxFallbackHandlerRegistry
import io.namastack.outbox.instrumentation.OutboxRecordProcessingOutcome
import io.namastack.outbox.runtime.OutboxRuntimeSettings
import io.namastack.outbox.runtime.toRuntimeSettings
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
 * @param settingsProvider Effective processing settings
 * @param clock Clock for completion timestamp
 *
 * @author Roland Beisel
 * @since 1.0.0
 */
class FallbackOutboxRecordProcessor private constructor(
    private val recordRepository: OutboxRecordRepository,
    private val fallbackHandlerRegistry: OutboxFallbackHandlerRegistry,
    private val fallbackHandlerInvoker: OutboxFallbackHandlerInvoker,
    private val settingsProvider: () -> OutboxRuntimeSettings.Processing,
    private val clock: Clock,
) : OutboxRecordProcessor() {
    /** Creates a processor from effective runtime settings. */
    constructor(
        recordRepository: OutboxRecordRepository,
        fallbackHandlerRegistry: OutboxFallbackHandlerRegistry,
        fallbackHandlerInvoker: OutboxFallbackHandlerInvoker,
        settings: OutboxRuntimeSettings.Processing,
        clock: Clock,
    ) : this(recordRepository, fallbackHandlerRegistry, fallbackHandlerInvoker, { settings }, clock)

    /** Creates a processor from Spring-bound properties. */
    constructor(
        recordRepository: OutboxRecordRepository,
        fallbackHandlerRegistry: OutboxFallbackHandlerRegistry,
        fallbackHandlerInvoker: OutboxFallbackHandlerInvoker,
        properties: OutboxProperties,
        clock: Clock,
    ) : this(
        recordRepository,
        fallbackHandlerRegistry,
        fallbackHandlerInvoker,
        { properties.toRuntimeSettings().processing },
        clock,
    )

    private val log = LoggerFactory.getLogger(FallbackOutboxRecordProcessor::class.java)

    /**
     * Processes record by dispatching to fallback handler.
     *
     * If no fallback handler is registered, delegates to the next processor in the chain.
     * If the fallback handler throws, stores the exception on the record and delegates to the next processor.
     *
     * @return [OutboxRecordProcessingOutcome.COMPLETED] if the fallback succeeds, otherwise the
     * outcome returned by the downstream chain.
     */
    override fun handle(record: OutboxRecord<*>): OutboxRecordProcessingOutcome {
        try {
            if (!fallbackHandlerRegistry.existsByHandlerId(record.handlerId)) {
                log.debug("No fallback handler registered for handlerId: {}", record.handlerId)
                return handleNext(record)
            }
            log.debug("Dispatching record {} to fallback handler", record.id)
            fallbackHandlerInvoker.dispatch(record)

            completeRecord(record, recordRepository, settingsProvider(), clock)

            return OutboxRecordProcessingOutcome.COMPLETED
        } catch (ex: Exception) {
            log.error("Fallback handler failed for record {}: {}", record.id, ex.message, ex)

            record.updateFailureException(ex)

            return handleNext(record)
        }
    }
}
