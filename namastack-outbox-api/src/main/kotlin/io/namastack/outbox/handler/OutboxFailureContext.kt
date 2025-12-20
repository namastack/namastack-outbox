package io.namastack.outbox.handler

/**
 * Context information about a failed outbox record.
 *
 * Provides information about why and how a record failed, enabling fallback handlers
 * to make informed decisions about compensating actions.
 *
 * Passed to handleFailure() methods:
 *
 * ```kotlin
 * override fun handleFailure(
 *     payload: OrderEvent,
 *     metadata: OutboxRecordMetadata,
 *     context: OutboxFailureContext
 * ) {
 *     if (context.retriesExhausted) {
 *         deadLetterQueue.publish(payload)
 *     }
 *     if (context.nonRetryableException) {
 *         compensationService.cancel(payload.orderId)
 *     }
 * }
 * ```
 *
 * A record can fail for two reasons:
 * 1. Retries Exhausted: failureCount exceeded maxRetries
 * 2. Non-Retryable Exception: Retry policy determined the exception should not be retried
 *
 * @property recordId Unique identifier of the failed outbox record
 * @property failureCount Total number of failed processing attempts
 * @property lastFailureReason Error message from the last failure, or null if not available
 * @property handlerId Unique identifier of the handler that failed processing
 * @property retriesExhausted True if the record failed because retry limit was reached
 * @property nonRetryableException True if the record failed due to a non-retryable exception
 *
 * @author Roland Beisel
 * @since 0.6.0
 */
data class OutboxFailureContext(
    val recordId: String,
    val failureCount: Int,
    val lastFailureReason: String?,
    val handlerId: String,
    val retriesExhausted: Boolean,
    val nonRetryableException: Boolean,
)
