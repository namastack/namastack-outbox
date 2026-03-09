package io.namastack.outbox.handler.invoker

import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.handler.method.handler.GenericHandlerMethod
import io.namastack.outbox.handler.method.handler.TypedHandlerMethod
import io.namastack.outbox.handler.registry.OutboxHandlerRegistry

/**
 * Invokes the appropriate handler for a given record.
 *
 * Routes outbox records to their registered handlers based on the handler ID
 * stored in the record. Handles both typed and generic handlers
 * with the correct parameter passing.
 *
 * @param handlerRegistry Registry of all registered handlers
 *
 * @author Roland Beisel
 * @since 0.4.0
 */
open class OutboxHandlerInvoker(
    private val handlerRegistry: OutboxHandlerRegistry,
) {
    /**
     * Dispatches a record to its registered handler.
     *
     * Algorithm:
     * 1. Skip if payload is null (nothing to process)
     * 2. Look up handler by ID from the record
     * 3. Invoke the handler with payload and metadata
     *
     * The handler ID comes from [OutboxRecord.handlerId], which was stored when
     * the record was originally scheduled.
     *
     * If a handler method throws an exception, the original exception is automatically
     * unwrapped from InvocationTargetException (reflection wrapper) and rethrown.
     * This ensures retry policies can match against the actual exception types.
     *
     * Example:
     * ```kotlin
     * val invoker = OutboxHandlerInvoker(registry)
     * invoker.dispatch(record)
     * ```
     *
     * @param record The record to process
     * @throws IllegalStateException if no handler with the given ID exists
     * @throws Throwable the original exception thrown by the handler (will trigger retries)
     */
    open fun dispatch(record: OutboxRecord<*>) {
        val payload = record.payload ?: return
        val metadata = record.toMetadata()

        val handler =
            handlerRegistry.getHandlerById(record.handlerId)
                ?: throw IllegalStateException("No handler with id ${record.handlerId}")

        when (handler) {
            is TypedHandlerMethod -> handler.invoke(payload, metadata)
            is GenericHandlerMethod -> handler.invoke(payload, metadata)
        }
    }
}
