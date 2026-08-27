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
 * ## Combining annotation- and interface-based handlers
 *
 * Annotation- and interface-based handler methods may coexist in the same bean. Each distinct
 * method is registered independently and keeps its own identity and fallback configuration.
 * However, a method provided by a handler interface must not also be annotated with
 * [OutboxHandler], because that would declare the same handler through both mechanisms.
 *
 * @see io.namastack.outbox.annotation.OutboxFallbackHandler
 * @see io.namastack.outbox.handler.OutboxTypedHandler
 * @see io.namastack.outbox.handler.OutboxHandler
 *
 * ## Stable identity and migration
 *
 * By default, the handler keeps its generated class-and-method ID. Set [id] to opt into a
 * deployment-stable canonical ID. Values in [aliases] are accepted for lookup of existing
 * records but are never written to new records or returned as additional handlers.
 *
 * A rolling rename uses two deployments: first add the future ID to [aliases], then promote it
 * to [id] while retaining the previous ID as an alias. When [id] is set, the generated legacy ID
 * is registered automatically where it can be reconstructed reliably.
 *
 * @property id optional canonical ID; empty retains the generated ID
 * @property aliases lookup-only IDs for persisted records
 * @author Roland Beisel
 * @since 0.4.0
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class OutboxHandler(
    val id: String = "",
    val aliases: Array<String> = [],
)
