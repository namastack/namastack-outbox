package io.namastack.outbox.annotation

/**
 * Marks a method as a fallback handler for failed outbox records.
 *
 * Invoked when a handler fails after all retry attempts are exhausted
 * or when a non-retryable exception occurs.
 *
 * ## Handler Signatures
 *
 * ```kotlin
 * @OutboxFallbackHandler
 * fun handleFailure(payload: OrderEvent, context: OutboxFailureContext)
 *
 * // Or generic:
 * @OutboxFallbackHandler
 * fun handleFailure(payload: Any, context: OutboxFailureContext)
 * ```
 *
 * ## Automatic Matching
 *
 * Fallback handlers are matched to primary handlers by payload type.
 * Multiple handlers can share the same fallback.
 *
 * ```kotlin
 * @Component
 * class OrderHandlers {
 *     @OutboxHandler
 *     fun handleOrder(payload: OrderEvent) {
 *         emailService.send(payload)
 *     }
 *
 *     @OutboxFallbackHandler
 *     fun handleOrderFailure(payload: OrderEvent, context: OutboxFailureContext) {
 *         deadLetterQueue.publish(payload)
 *     }
 * }
 * ```
 *
 * ## Failure Context
 *
 * OutboxFailureContext provides: recordId, recordKey, createdAt, handlerId,
 * failureCount, retriesExhausted, nonRetryableException, lastFailure, context map.
 *
 * ## Notes
 *
 * - Do NOT mix annotations with interface-based handlers in the same bean
 * - If multiple fallbacks exist for the same type, first one is used (warning logged)
 * - Exceptions from fallback handlers are logged but don't trigger retries
 * - Record is marked FAILED if fallback fails, COMPLETED if fallback succeeds
 *
 * @see io.namastack.outbox.annotation.OutboxHandler
 * @see io.namastack.outbox.handler.OutboxFailureContext
 *
 * @author Roland Beisel
 * @since 1.0.0
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class OutboxFallbackHandler
