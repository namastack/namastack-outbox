package io.namastack.outbox.handler

import java.time.OffsetDateTime

/**
 * Context for fallback handlers about permanently failed records.
 *
 * Provides details about handler failure to enable informed compensating actions.
 *
 * Example usage:
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
 * Records fail permanently for two reasons:
 * 1. Retries exhausted: failureCount exceeded maxRetries
 * 2. Non-retryable exception: retry policy rejected the exception
 *
 * @property recordId Unique identifier of the failed record
 * @property recordKey Business key of the record
 * @property createdAt When the record was created
 * @property failureCount Total number of failed processing attempts
 * @property lastFailure The exception that caused the last handler failure
 * @property handlerId Unique identifier of the failed handler
 * @property retriesExhausted True if retry limit was reached
 * @property nonRetryableException True if failure was due to non-retryable exception
 *
 * @author Roland Beisel
 * @since 0.5.0
 */
data class OutboxFailureContext(
    val recordId: String,
    val recordKey: String,
    val createdAt: OffsetDateTime,
    val failureCount: Int,
    val lastFailure: Throwable?,
    val handlerId: String,
    val retriesExhausted: Boolean,
    val nonRetryableException: Boolean,
)
