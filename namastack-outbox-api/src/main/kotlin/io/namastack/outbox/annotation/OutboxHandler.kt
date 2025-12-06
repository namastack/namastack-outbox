package io.namastack.outbox.annotation

/**
 * Marks a method as an outbox record handler.
 *
 * Supports two distinct handler signatures:
 *
 * **Typed Handler (1 parameter):**
 * ```kotlin
 * @OutboxHandler
 * fun handle(payload: OrderCreatedEvent) {
 *     // Handle specific event type
 *     eventBus.publish(payload)
 * }
 * ```
 * Records with matching payload type are routed to this handler.
 *
 * **Generic Handler (2 parameters):**
 * ```kotlin
 * @OutboxHandler
 * fun handle(payload: Any, metadata: OutboxRecordMetadata) {
 *     // Handle any payload type
 *     when (payload) {
 *         is OrderCreated -> handleOrder(payload)
 *         is PaymentProcessed -> handlePayment(payload)
 *         else -> logger.warn("Unknown payload type")
 *     }
 * }
 * ```
 * Receives all records regardless of payload type, with full metadata context.
 *
 * ## Invocation Order
 *
 * If both a typed handler and a generic handler match a record:
 * 1. Typed handler is invoked first (if payload type matches)
 * 2. Generic handler is invoked second (always)
 *
 * ## Exception Handling
 *
 * Exceptions thrown from handlers trigger automatic retries based on the configured
 * retry policy. Successfully completing (no exception) marks the record as processed.
 *
 * @author Roland Beisel
 * @since 0.4.0
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class OutboxHandler
