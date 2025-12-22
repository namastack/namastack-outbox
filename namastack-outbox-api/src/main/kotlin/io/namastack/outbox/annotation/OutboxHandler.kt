package io.namastack.outbox.annotation

/**
 * Marks a method as an outbox record handler.
 *
 * Supports two distinct handler signatures:
 *
 * Typed Handler (1 parameter):
 * ```kotlin
 * @OutboxHandler
 * fun handle(payload: OrderCreatedEvent) {
 *     eventBus.publish(payload)
 * }
 * ```
 *
 * Generic Handler (2 parameters):
 * ```kotlin
 * @OutboxHandler
 * fun handle(payload: Any, metadata: OutboxRecordMetadata) {
 *     when (payload) {
 *         is OrderCreated -> handleOrder(payload)
 *         is PaymentProcessed -> handlePayment(payload)
 *         else -> logger.warn("Unknown payload type")
 *     }
 * }
 * ```
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
 * retry policy. Successfully completing marks the record as processed.
 *
 * ## Important: Do NOT mix with Interface-based Handlers
 *
 * Do not combine @OutboxHandler annotations with interface-based handlers
 * in the same bean. This can lead to unexpected behavior such as incorrect
 * fallback matching and ambiguous handler registration.
 *
 * Use EITHER annotations OR interfaces per bean, not both.
 *
 * @see io.namastack.outbox.annotation.OutboxFallbackHandler
 * @see io.namastack.outbox.handler.OutboxTypedHandler
 * @see io.namastack.outbox.handler.OutboxHandler
 * @author Roland Beisel
 * @since 0.4.0
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class OutboxHandler
