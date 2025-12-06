package io.namastack.outbox.handler

/**
 * Handler for processing outbox records with a specific payload type.
 *
 * Implementations define the business logic for processing records with payloads
 * of type [T]. Handlers are invoked by the outbox scheduler for each record
 * that is ready for processing.
 *
 * ## Implementation Guidelines
 *
 * - **Idempotency**: Handlers should be idempotent. Records may be processed
 *   multiple times due to retries or system failures.
 * - **Exception Handling**: Throw exceptions to signal processing failure and trigger
 *   automatic retries. Return normally to mark the record as successfully processed.
 * - **Side Effects**: Keep side effects minimal and idempotent (e.g., API calls with
 *   idempotency keys, database operations with unique constraints).
 *
 * ## Example
 *
 * ```kotlin
 * @Component
 * class OrderCreatedHandler : OutboxTypedHandler<OrderCreatedPayload> {
 *     override fun handle(payload: OrderCreatedPayload) {
 *         // Publish to event bus, send email, update cache, etc.
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
     * Called by the outbox scheduler when a record is ready for processing.
     * Implementations should handle the payload and complete successfully or throw
     * an exception to trigger retries.
     *
     * @param payload The record payload of type [T]
     *
     * @throws Exception to signal processing failure and trigger automatic retries
     *                   based on the configured retry policy
     */
    fun handle(payload: T)
}
