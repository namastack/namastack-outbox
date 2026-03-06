package io.namastack.outbox.handler.invoker

import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.handler.registry.OutboxFallbackHandlerRegistry
import io.namastack.outbox.retry.OutboxRetryPolicyRegistry
import org.slf4j.LoggerFactory

/**
 * Invokes fallback handlers for failed outbox records.
 *
 * Routes failed records to their registered fallback handlers based on handler ID
 * from the record's metadata.
 *
 * @param fallbackHandlerRegistry Registry of all registered fallback handlers
 * @author Roland Beisel
 * @since 1.0.0
 */
open class OutboxFallbackHandlerInvoker(
    private val retryPolicyRegistry: OutboxRetryPolicyRegistry,
    private val fallbackHandlerRegistry: OutboxFallbackHandlerRegistry,
) {
    private val log = LoggerFactory.getLogger(OutboxFallbackHandlerInvoker::class.java)

    /**
     * Invokes the fallback handler for a failed record.
     *
     * Looks up fallback handler by handlerId and invokes it with payload and
     * failure context. If no fallback is registered, logs debug and returns false.
     *
     * @param record The record to process
     * @return true if fallback handler was invoked, false if no handler registered or payload is null
     */
    fun dispatch(record: OutboxRecord<*>): Boolean {
        val payload = record.payload ?: return false
        val context = record.toFailureContext(getFailureException(record), retryPolicyRegistry)

        val fallbackHandler =
            fallbackHandlerRegistry.getByHandlerId(record.handlerId) ?: run {
                log.debug("No fallback handler registered for handlerId: {}", record.handlerId)
                return false
            }

        fallbackHandler.invoke(payload, context)

        return true
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
