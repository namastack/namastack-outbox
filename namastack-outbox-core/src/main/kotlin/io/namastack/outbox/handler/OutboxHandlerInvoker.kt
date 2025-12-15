package io.namastack.outbox.handler

import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.handler.method.GenericHandlerMethod
import io.namastack.outbox.handler.method.OutboxHandlerMethod
import io.namastack.outbox.handler.method.TypedHandlerMethod
import io.namastack.outbox.interceptor.OutboxDeliveryInterceptorChain
import io.namastack.outbox.interceptor.OutboxDeliveryInterceptorContext

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
    private val interceptor: OutboxDeliveryInterceptorChain,
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
        val handler =
            handlerRegistry.getHandlerById(record.handlerId)
                ?: throw IllegalStateException("No handler with id ${record.handlerId}")
        val context = createDeliveryInterceptorContext(record, handler)

        try {
            interceptor.applyBeforeHandler(context)
            // Invoke handler based on type (typed vs generic)
            when (handler) {
                is TypedHandlerMethod -> handler.invoke(payload)
                is GenericHandlerMethod -> handler.invoke(payload, metadata)
            }
            interceptor.applyAfterHandler(context)
        } catch (ex: Exception) {
            interceptor.applyOnError(context, ex)
            // Rethrow to trigger retry logic in caller
            throw ex
        } finally {
            interceptor.applyAfterCompletion(context)
        }
    }

    private fun createMetadata(record: OutboxRecord<*>): OutboxRecordMetadata =
        OutboxRecordMetadata(
            key = record.key,
            handlerId = record.handlerId,
            createdAt = record.createdAt,
        )

    fun createDeliveryInterceptorContext(
        record: OutboxRecord<*>,
        handler: OutboxHandlerMethod,
    ): OutboxDeliveryInterceptorContext =
        OutboxDeliveryInterceptorContext(
            key = record.key,
            attributes = record.attributes,
            handlerId = record.handlerId,
            handlerClass = handler.bean::class.java,
            handlerMethod = handler.method,
            failureCount = record.failureCount,
            createdAt = record.createdAt,
        )
}
