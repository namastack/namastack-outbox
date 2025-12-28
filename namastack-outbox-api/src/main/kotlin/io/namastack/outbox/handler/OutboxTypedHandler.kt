package io.namastack.outbox.handler

/**
 * Handler for processing outbox records with a specific payload type.
 *
 * Handlers are invoked by the outbox scheduler for each record ready for processing.
 * Implementations should be idempotent as records may be processed multiple times
 * due to retries or system failures.
 *
 * The metadata parameter provides access to record context including key, handler ID,
 * creation timestamp, and custom context (e.g., tracing IDs, tenant info).
 *
 * Example:
 *
 * ```kotlin
 * @Component
 * class OrderCreatedHandler : OutboxTypedHandler<OrderCreatedPayload> {
 *     override fun handle(payload: OrderCreatedPayload, metadata: OutboxRecordMetadata) {
 *         val traceId = metadata.context["traceId"]
 *         eventBus.publish(payload, traceId)
 *     }
 * }
 * ```
 *
 * If you don't need metadata, simply ignore the parameter:
 *
 * ```kotlin
 * @Component
 * class OrderCreatedHandler : OutboxTypedHandler<OrderCreatedPayload> {
 *     override fun handle(payload: OrderCreatedPayload, metadata: OutboxRecordMetadata) {
 *         eventBus.publish(payload)
 *     }
 * }
 * ```
 *
 * @param T The type of the payload this handler processes
 *
 * @author Roland Beisel
 * @since 0.4.0
 */
interface OutboxTypedHandler<T> {
    /**
     * Handles an outbox record with payload and metadata.
     *
     * @param payload The record payload of type [T]
     * @param metadata Record metadata (key, handlerId, context, createdAt)
     * @throws Exception to signal processing failure and trigger automatic retries
     */
    fun handle(
        payload: T,
        metadata: OutboxRecordMetadata,
    )
}
