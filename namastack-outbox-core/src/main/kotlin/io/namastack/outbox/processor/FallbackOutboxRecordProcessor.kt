package io.namastack.outbox.processor

import io.namastack.outbox.OutboxProperties
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.handler.invoker.OutboxFallbackHandlerInvoker
import io.namastack.outbox.retry.OutboxRetryPolicyRegistry
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * Processor that handles permanently failed records by invoking fallback handlers.
 *
 * On success: marks record as COMPLETED or deletes it.
 * On failure: stores fallback exception, passes to next processor in chain.
 *
 * @param recordRepository Repository for persisting record state
 * @param fallbackHandlerInvoker Dispatcher for fallback handlers
 * @param retryPolicyRegistry Registry for retry policies
 * @param properties Configuration
 * @param clock Clock for completion timestamp
 *
 * @author Roland Beisel
 * @since 1.0.0
 */
class FallbackOutboxRecordProcessor(
    private val recordRepository: OutboxRecordRepository,
    private val fallbackHandlerInvoker: OutboxFallbackHandlerInvoker,
    private val retryPolicyRegistry: OutboxRetryPolicyRegistry,
    private val properties: OutboxProperties,
    private val clock: Clock,
) : OutboxRecordProcessor() {
    private val log = LoggerFactory.getLogger(FallbackOutboxRecordProcessor::class.java)

    /**
     * Processes record by dispatching to fallback handler.
     *
     * @return true if fallback handler succeeded, false if next processor should process
     */
    override fun handle(record: OutboxRecord<*>): Boolean {
        try {
            val failureException = getFailureException(record)

            log.debug("Dispatching record {} to fallback handler", record.id)
            val success =
                fallbackHandlerInvoker.dispatch(
                    payload = record.payload,
                    context = record.toFailureContext(failureException, retryPolicyRegistry),
                )

            if (!success) return handleNext(record)

            completeRecord(record, recordRepository, properties, clock)

            return true
        } catch (ex: Exception) {
            log.error("Fallback handler failed for record {}: {}", record.id, ex.message, ex)

            record.updateFailureException(ex)

            return handleNext(record)
        }
    }

    /**
     * Gets failure exception from record.
     * Exception must exist since this processor only handles failed records.
     */
    private fun getFailureException(record: OutboxRecord<*>): Throwable =
        checkNotNull(record.failureException) {
            "Expected failure exception in record ${record.id} but found none"
        }
}
