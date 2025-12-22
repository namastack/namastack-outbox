package io.namastack.outbox.handler

/**
 * Handler for processing outbox records with a specific payload type.
 *
 * Handlers are invoked by the outbox scheduler for each record ready for processing.
 * Implementations should be idempotent as records may be processed multiple times
 * due to retries or system failures.
 *
 * Example:
 *
 * ```kotlin
 * @Component
 * class OrderCreatedHandler : OutboxTypedHandler<OrderCreatedPayload> {
 *     override fun handle(payload: OrderCreatedPayload) {
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
     * Handles an outbox record with the given payload.
     *
     * @param payload The record payload of type [T]
     * @throws Exception to signal processing failure and trigger automatic retries
     */
    fun handle(payload: T)
}
