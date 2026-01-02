package io.namastack.outbox.handler.invoker

import io.namastack.outbox.handler.OutboxFailureContext
import io.namastack.outbox.handler.registry.OutboxFallbackHandlerRegistry
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
class OutboxFallbackHandlerInvoker(
    private val fallbackHandlerRegistry: OutboxFallbackHandlerRegistry,
) {
    private val log = LoggerFactory.getLogger(OutboxFallbackHandlerInvoker::class.java)

    /**
     * Invokes the fallback handler for a failed record.
     *
     * Looks up fallback handler by handlerId and invokes it with payload and
     * failure context. If no fallback is registered, logs debug and returns false.
     *
     * @param payload Record payload
     * @param context Failure details
     * @return true if fallback handler was invoked, false if no handler registered or payload is null
     */
    fun dispatch(
        payload: Any?,
        context: OutboxFailureContext,
    ): Boolean {
        if (payload == null) return false

        val fallbackHandler =
            fallbackHandlerRegistry.getByHandlerId(context.handlerId) ?: run {
                log.debug("No fallback handler registered for handlerId: {}", context.handlerId)
                return false
            }

        fallbackHandler.invoke(payload, context)

        return true
    }
}
