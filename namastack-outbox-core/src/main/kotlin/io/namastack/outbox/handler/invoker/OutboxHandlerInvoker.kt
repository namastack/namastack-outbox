package io.namastack.outbox.handler.invoker

import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.handler.method.handler.GenericHandlerMethod
import io.namastack.outbox.handler.method.handler.TypedHandlerMethod
import io.namastack.outbox.handler.registry.OutboxHandlerRegistry

/**
 * Invokes the appropriate handler for a given record.
 *
 * Routes outbox records to their registered handlers based on the handler ID
 * stored in the record's metadata. Handles both typed and generic handlers
 * with the correct parameter passing.
 *
 * Replaces the previous OutboxDispatcher with a more semantically correct name.
 *
 * @param handlerRegistry Registry of all registered handlers
 *
 * @author Roland Beisel
 * @since 0.4.0
 */
class OutboxHandlerInvoker(
    private val handlerRegistry: OutboxHandlerRegistry,
) {
    /**
     * Dispatches a record to its registered handler.
     *
     * Algorithm:
     * 1. Skip if payload is null (nothing to process)
     * 2. Look up handler by ID from metadata
     * 3. Invoke the handler with payload and metadata
     *
     * The handler ID comes from metadata.handlerId, which was stored when
     * the record was originally scheduled.
     *
     * If a handler method throws an exception, the original exception is automatically
     * unwrapped from InvocationTargetException (reflection wrapper) and rethrown.
     * This ensures retry policies can match against the actual exception types.
     *
     * Example:
     * ```kotlin
     * val invoker = OutboxHandlerInvoker(registry)
     * val metadata = OutboxRecordMetadata(key = "order-1", handlerId = "...", createdAt = now)
     * invoker.dispatch(OrderCreatedEvent(...), metadata)
     * ```
     *
     * @param payload The record payload to process
     * @param metadata Record metadata containing handler ID and context
     * @throws IllegalStateException if no handler with the given ID exists
     * @throws Exception the original exception thrown by the handler (will trigger retries)
     */
    fun dispatch(
        payload: Any?,
        metadata: OutboxRecordMetadata,
    ) {
        if (payload == null) return

        val handler =
            handlerRegistry.getHandlerById(metadata.handlerId)
                ?: throw IllegalStateException("No handler with id ${metadata.handlerId}")

        when (handler) {
            is TypedHandlerMethod -> handler.invoke(payload, metadata)
            is GenericHandlerMethod -> handler.invoke(payload, metadata)
        }
    }
}
