package io.namastack.outbox.handler.invoker

import io.namastack.outbox.handler.OutboxFailureContext
import io.namastack.outbox.handler.OutboxRecordMetadata
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
 * @since 0.5.0
 */
class OutboxFallbackHandlerInvoker(
    private val fallbackHandlerRegistry: OutboxFallbackHandlerRegistry,
) {
    private val log = LoggerFactory.getLogger(OutboxFallbackHandlerInvoker::class.java)

    /**
     * Invokes the fallback handler for a failed record.
     *
     * Looks up fallback handler by handlerId and invokes it with payload, metadata,
     * and failure context. If no fallback is registered, logs debug and returns.
     *
     * @param payload Record payload
     * @param metadata Record metadata with handler ID
     * @param context Failure details
     */
    fun dispatch(
        payload: Any?,
        metadata: OutboxRecordMetadata,
        context: OutboxFailureContext,
    ): Boolean {
        if (payload == null) return false

        val fallbackHandler =
            fallbackHandlerRegistry.getByHandlerId(metadata.handlerId) ?: run {
                log.debug("No fallback handler registered for handlerId: {}", metadata.handlerId)
                return false
            }

        fallbackHandler.invoke(payload, metadata, context)

        return true
    }
}
