package io.namastack.outbox.handler

import java.time.OffsetDateTime

/**
 * Context for fallback handlers about permanently failed records.
 *
 * Provides details about handler failure to enable informed compensating actions.
 * Includes the original context (e.g., tracing IDs, tenant info) from the record.
 *
 * Example usage:
 * ```kotlin
 * override fun handleFailure(
 *     payload: OrderEvent,
 *     context: OutboxFailureContext
 * ) {
 *     val traceId = context.context["traceId"]
 *     logger.error("Handler failed for order ${payload.orderId} (trace: $traceId)")
 *
 *     if (context.retriesExhausted) {
 *         deadLetterQueue.publish(payload, traceId)
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
 * @property context Custom context from the original record (tracing, tenancy, correlation)
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
    val context: Map<String, String>,
)
