package io.namastack.outbox.handler

/**
 * Handler for processing outbox records of any payload type with custom fallback logic.
 *
 * Extends [OutboxHandler] by adding a generic fallback mechanism invoked when
 * record processing fails after all retry attempts are exhausted or when a
 * non-retryable exception occurs.
 *
 * Example:
 *
 * ```kotlin
 * @Component
 * class GenericOutboxHandler : OutboxHandlerWithFallback {
 *     override fun handle(payload: Any, metadata: OutboxRecordMetadata) {
 *         when (payload) {
 *             is OrderCreatedEvent -> handleOrder(payload)
 *             is PaymentProcessedEvent -> handlePayment(payload)
 *             else -> logger.warn("Unknown payload type")
 *         }
 *     }
 *
 *     override fun handleFailure(
 *         payload: Any,
 *         metadata: OutboxRecordMetadata,
 *         context: OutboxFailureContext
 *     ) {
 *         when (payload) {
 *             is CriticalEvent -> alertingService.alert(payload, context)
 *             is PaymentEvent -> refundService.initiateRefund(payload)
 *             else -> deadLetterQueue.publish(payload, context)
 *         }
 *     }
 * }
 * ```
 *
 * Both handle() and handleFailure() should be idempotent. Exceptions in handleFailure()
 * are logged but do not trigger retries.
 *
 * @author Roland Beisel
 * @since 0.5.0
 */
interface OutboxHandlerWithFallback : OutboxHandler {
    /**
     * Handles a failed outbox record after all retry attempts are exhausted.
     *
     * Called when record has failed and failureCount exceeds maxRetries or when
     * exception is non-retryable according to retry policy.
     *
     * Exceptions thrown from this method are logged but do not trigger retries.
     *
     * @param payload The record payload of any type
     * @param metadata Record metadata
     * @param context Failure context with details about the failure
     */
    fun handleFailure(
        payload: Any,
        metadata: OutboxRecordMetadata,
        context: OutboxFailureContext,
    )
}
