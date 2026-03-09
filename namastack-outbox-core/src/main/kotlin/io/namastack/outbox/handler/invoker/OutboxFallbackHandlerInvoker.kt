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
 * @param retryPolicyRegistry Registry to look up retry policies per handler
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
     * Looks up the fallback handler by handlerId from the record's metadata and invokes it
     * with the record's payload and failure context. If no fallback handler is registered
     * or the payload is null, logs debug and returns false.
     *
     * @param record The failed record to dispatch to a fallback handler
     * @return true if the fallback handler was invoked successfully, false if no handler is
     *   registered for the record's handlerId or the record's payload is null
     */
    open fun dispatch(record: OutboxRecord<*>): Boolean {
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
     * Gets the failure exception from a record.
     * The exception must be present since this invoker only handles failed records.
     */
    private fun getFailureException(record: OutboxRecord<*>): Throwable =
        checkNotNull(record.failureException) {
            "Expected failure exception in record ${record.id} but found none"
        }
}
