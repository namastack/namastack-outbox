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
 * ## Stable logical handler ID
 *
 * By default the persisted `handler_id` is derived from the handler class FQCN, method name,
 * and parameter FQCNs.  Renaming or moving the handler class (or any event class in the
 * method signature) causes pending outbox rows to fail dispatch.
 *
 * Set `name` to decouple the persisted ID from Java package structure:
 *
 * ```kotlin
 * @OutboxHandler(name = "orders.processor")
 * fun handle(payload: OrderCreatedEvent) { ... }
 * ```
 *
 * After renaming or moving the handler class, add the old FQCN-based ID as an alias so
 * rows written before the rename continue to dispatch:
 *
 * ```kotlin
 * @OutboxHandler(
 *     name = "orders.processor",
 *     aliases = ["com.acme.v1.OrderHandler#handle(com.acme.order.OrderCreatedEvent)"]
 * )
 * fun handle(payload: OrderCreatedEvent) { ... }
 * ```
 *
 * Once all rows referencing the old ID have been processed, remove the alias.
 *
 * For interface-based handlers use [@OutboxHandlerId][OutboxHandlerId] on the class instead.
 *
 * ## Important: Do NOT mix with Interface-based Handlers
 *
 * Do not combine @OutboxHandler annotations with interface-based handlers
 * in the same bean. This can lead to unexpected behavior such as incorrect
 * fallback matching and ambiguous handler registration.
 *
 * Use EITHER annotations OR interfaces per bean, not both.
 *
 * @param name Optional stable logical handler identifier. When blank (the default), the FQCN-based
 *             ID is used unchanged. Must not contain `#`, `,`, `(`, `)`, or whitespace.
 *             Equivalent to [value] — `@OutboxHandler("orders.process")` and
 *             `@OutboxHandler(name = "orders.process")` are interchangeable; [name] wins
 *             when both are set.
 * @param value Shorthand alias for [name] — enables positional syntax `@OutboxHandler("orders.process")`.
 * @param aliases Additional identifiers that should resolve to this handler. Use these to absorb
 *                old FQCN-based IDs written before a class or method rename.
 *
 * @see io.namastack.outbox.annotation.OutboxFallbackHandler
 * @see io.namastack.outbox.annotation.OutboxHandlerId
 * @see io.namastack.outbox.handler.OutboxTypedHandler
 * @see io.namastack.outbox.handler.OutboxHandler
 * @author Roland Beisel
 * @since 0.4.0
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class OutboxHandler(
    val name: String = "",
    val value: String = "",
    val aliases: Array<String> = [],
)
