package io.namastack.outbox.handler

import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.context.OutboxContextPropagator
import io.namastack.outbox.context.OutboxContextPropagator.Scope
import io.namastack.outbox.handler.method.GenericHandlerMethod
import io.namastack.outbox.handler.method.TypedHandlerMethod

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
 */
class OutboxHandlerInvoker(
    private val handlerRegistry: OutboxHandlerRegistry,
    private val propagators: List<OutboxContextPropagator>,
) {
    /**
     * Dispatches a record to its registered handler.
     *
     * Algorithm:
     * 1. Skip if payload is null (nothing to process)
     * 2. Look up handler by ID from metadata
     * 3. Invoke the handler based on its type:
     *    - TypedHandlerMethod: Call with just payload parameter
     *    - GenericHandlerMethod: Call with payload AND metadata parameters
     *
     * The handler ID comes from metadata.handlerId, which was stored when
     * the record was originally scheduled.
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
     * @throws Exception if the handler method throws (will trigger retries)
     */
    fun dispatch(record: OutboxRecord<*>) {
        val payload: Any = record.payload ?: return
        val metadata = createMetadata(record)
        val scopes = openScopes(record)
        val handler =
            handlerRegistry.getHandlerById(record.handlerId)
                ?: throw IllegalStateException("No handler with id ${record.handlerId}")

        try {
            // Invoke handler based on type (typed vs generic)
            when (handler) {
                is TypedHandlerMethod -> handler.invoke(payload)
                is GenericHandlerMethod -> handler.invoke(payload, metadata)
            }
            scopes.onSuccess()
        } catch (ex: Exception) {
            scopes.onError(ex)
            // Rethrow to trigger retry logic in caller
            throw ex
        } finally {
            scopes.close()
        }
    }

    private fun createMetadata(record: OutboxRecord<*>): OutboxRecordMetadata =
        OutboxRecordMetadata(
            key = record.key,
            handlerId = record.handlerId,
            createdAt = record.createdAt,
        )

    fun openScopes(record: OutboxRecord<*>): Scope {
        val scopes = ArrayDeque<Scope>()
        try {
            propagators.forEach { p ->
                scopes.addFirst(p.openScope(record))
            }
        } catch (ex: Exception) {
            scopes.forEach { scope -> runCatching { scope.onError(ex) } }
            throw ex
        }

        return object : Scope {
            override fun onSuccess() {
                scopes.forEach { scope -> runCatching { scope.onSuccess() } }
            }

            override fun onError(error: Exception) {
                scopes.forEach { scope -> runCatching { scope.onError(error) } }
            }

            override fun close() {
                scopes.forEach { scope -> runCatching { scope.close() } }
            }
        }
    }
}
