package io.namastack.outbox.handler

/**
 * Handler for processing outbox records with a specific payload type and custom fallback logic.
 *
 * Extends [OutboxTypedHandler] by adding a type-safe fallback mechanism invoked when
 * record processing fails after all retry attempts are exhausted or when a
 * non-retryable exception occurs.
 *
 * Example:
 *
 * ```kotlin
 * @Component
 * class OrderCreatedHandler : OutboxTypedHandlerWithFallback<OrderCreatedEvent> {
 *     override fun handle(payload: OrderCreatedEvent) {
 *         eventBus.publish(payload)
 *     }
 *
 *     override fun handleFailure(
 *         payload: OrderCreatedEvent,
 *         metadata: OutboxRecordMetadata,
 *         context: OutboxFailureContext
 *     ) {
 *         deadLetterQueue.publish(payload)
 *         alertingService.alert("Order failed after ${context.failureCount} attempts")
 *         compensationService.cancelOrder(payload.orderId)
 *     }
 * }
 * ```
 *
 * Both handle() and handleFailure() should be idempotent. Exceptions in handleFailure()
 * are logged but do not trigger retries.
 *
 * @param T The type of the payload this handler processes
 *
 * @author Roland Beisel
 * @since 0.6.0
 */
interface OutboxTypedHandlerWithFallback<T> : OutboxTypedHandler<T> {
    /**
     * Handles a failed outbox record after all retry attempts are exhausted.
     *
     * Called when record has failed and failureCount exceeds maxRetries or when
     * exception is non-retryable according to retry policy.
     *
     * Exceptions thrown from this method are logged but do not trigger retries.
     *
     * @param payload The record payload of type [T]
     * @param metadata Record metadata
     * @param context Failure context with details about the failure
     */
    fun handleFailure(
        payload: T,
        metadata: OutboxRecordMetadata,
        context: OutboxFailureContext,
    )
}
