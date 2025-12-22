package io.namastack.outbox.annotation

/**
 * Marks a method as a fallback handler for failed outbox records.
 *
 * Fallback handlers are invoked when a handler fails after all retry attempts
 * are exhausted or when a non-retryable exception occurs.
 *
 * ## Important: Do NOT mix with Interface-based Handlers
 *
 * Do not combine @OutboxFallbackHandler annotations with interface-based handlers
 * in the same bean. This can lead to unexpected behavior.
 * Use EITHER annotations OR interfaces per bean.
 *
 * ## Automatic Matching via Payload Type
 *
 * Fallback handlers are automatically matched to handlers based on the payload type
 * of the first parameter.
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
 *     fun handleOrderFailure(
 *         payload: OrderEvent,
 *         metadata: OutboxRecordMetadata,
 *         context: OutboxFailureContext
 *     ) {
 *         deadLetterQueue.publish(payload)
 *     }
 * }
 * ```
 *
 * ## Handler Signatures
 *
 * Typed Fallback (3 parameters):
 * ```kotlin
 * @OutboxFallbackHandler
 * fun handleFailure(
 *     payload: OrderEvent,
 *     metadata: OutboxRecordMetadata,
 *     context: OutboxFailureContext
 * )
 * ```
 *
 * Generic Fallback (3 parameters):
 * ```kotlin
 * @OutboxFallbackHandler
 * fun handleFailure(
 *     payload: Any,
 *     metadata: OutboxRecordMetadata,
 *     context: OutboxFailureContext
 * )
 * ```
 *
 * ## Multiple Handlers, One Fallback
 *
 * Multiple handlers can share the same fallback if they have matching payload types.
 *
 * ```kotlin
 * @OutboxHandler
 * fun handleOrderEmail(payload: OrderEvent) { ... }
 *
 * @OutboxHandler
 * fun handleOrderKafka(payload: OrderEvent) { ... }
 *
 * @OutboxFallbackHandler
 * fun orderFallback(payload: OrderEvent, metadata: ..., context: ...) { ... }
 * ```
 *
 * ## Multiple Fallbacks for Same Type
 *
 * If multiple fallback handlers exist for the same payload type, the first one found
 * will be used based on declaration order. A warning will be logged.
 *
 * ## Failure Context
 *
 * The OutboxFailureContext provides information about the failure:
 * - retriesExhausted: true if retry limit was reached
 * - nonRetryableException: true if exception was non-retryable
 * - failureCount: number of failed attempts
 * - lastFailureReason: error message from last failure
 *
 * ## Exception Handling
 *
 * Exceptions thrown from fallback handlers are logged but do not trigger retries.
 * The record is marked as FAILED regardless of fallback handler outcome.
 *
 * @see io.namastack.outbox.annotation.OutboxHandler
 * @see io.namastack.outbox.handler.OutboxFailureContext
 * @author Roland Beisel
 * @since 0.5.0
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class OutboxFallbackHandler
