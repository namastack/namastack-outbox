package io.namastack.outbox.annotation

/**
 * Assigns a stable logical identifier to an interface-based outbox handler.
 *
 * Place this annotation on the implementing class when using the interface-based handler
 * style ([io.namastack.outbox.handler.OutboxTypedHandler] /
 * [io.namastack.outbox.handler.OutboxHandler]).  For annotation-based handlers use the
 * `id` attribute on [@OutboxHandler][OutboxHandler] instead.
 *
 * ## Why use a logical ID?
 *
 * Without this annotation the persisted `handler_id` is derived from the Java class name,
 * method name, and parameter FQCNs.  Moving or renaming the handler class (or any of the
 * event classes in its signature) causes pending outbox rows to fail deserialization because
 * the stored ID no longer resolves.
 *
 * A logical ID decouples the persisted `handler_id` from Java package structure.
 * Existing rows written under the old FQCN-based ID are still dispatched via an
 * automatically registered alias, so no backfill or migration is required.
 *
 * ## Example
 *
 * ```kotlin
 * @Component
 * @OutboxHandlerId("orders.processor")
 * class OrderHandler : OutboxTypedHandler<OrderCreatedEvent> {
 *     override fun handle(payload: OrderCreatedEvent) { ... }
 * }
 * ```
 *
 * After renaming the class from `OrderHandlerV1` to `OrderHandler`, add the old
 * FQCN-based ID as an alias so pending rows continue to resolve:
 *
 * ```kotlin
 * @Component
 * @OutboxHandlerId(
 *     value = "orders.processor",
 *     aliases = ["com.acme.order.OrderHandlerV1#handle(com.acme.order.OrderCreatedEvent,io.namastack.outbox.handler.OutboxRecordMetadata)"]
 * )
 * class OrderHandler : OutboxTypedHandler<OrderCreatedEvent> { ... }
 * ```
 *
 * Once all outbox rows that reference the old ID have been processed, remove the alias.
 *
 * ## Sealed class note
 *
 * Kotlin annotations are **not inherited**. Annotate each concrete subtype individually
 * when using sealed hierarchies.
 *
 * @param value The stable logical handler identifier (must not contain `#`, `,`, `(`, `)`,
 *              or whitespace — these characters are reserved for the FQCN-based ID format)
 * @param aliases Additional identifiers that should resolve to this handler. Use these to
 *                absorb old FQCN-based IDs written before a class rename.
 *
 * @see OutboxHandler
 * @author namastack-outbox contributors
 * @since 2.0.0
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class OutboxHandlerId(
    val value: String,
    val aliases: Array<String> = [],
)
