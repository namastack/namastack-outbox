package io.namastack.outbox.annotation

/**
 * Marks a payload class for automatic outbox persistence.
 *
 * When a payload annotated with @OutboxEvent is published via Spring's ApplicationEventPublisher,
 * it will be automatically persisted to the outbox database before being delivered to handlers.
 *
 * ## Context: Annotation vs Provider
 *
 * There are two ways to add context metadata to outbox records:
 *
 * ### 1. Event-Specific Context (Use `context` attribute)
 * Use the `context` attribute for metadata that is **unique to this event type** and derived from the event payload:
 * ```kotlin
 * @OutboxEvent(
 *     key = "#this.orderId",
 *     context = [
 *         OutboxContextEntry(key = "customerId", value = "#this.customerId"),
 *         OutboxContextEntry(key = "orderTotal", value = "#this.total.toString()")
 *     ]
 * )
 * data class OrderConfirmedEvent(
 *     val orderId: String,
 *     val customerId: String,
 *     val total: BigDecimal
 * )
 * ```
 *
 * ### 2. Global Context (Use `OutboxContextProvider`)
 * For metadata that should be added to **all outbox records** (e.g., tracing, tenant, correlation IDs),
 * implement [io.namastack.outbox.context.OutboxContextProvider]:
 * ```kotlin
 * @Component
 * class TracingContextProvider : OutboxContextProvider {
 *     override fun provide(): Map<String, String> {
 *         return mapOf(
 *             "traceId" to MDC.get("traceId"),
 *             "spanId" to MDC.get("spanId")
 *         ).filterValues { it != null }
 *     }
 * }
 * ```
 *
 * This way, you don't need to repeat tracing context in every `@OutboxEvent` annotation.
 * Both approaches can be combined - the final context is a merge of annotation context and provider context.
 *
 * ## Examples
 *
 * ### Basic Usage
 * ```kotlin
 * @OutboxEvent(key = "#this.orderId")
 * data class OrderCreatedEvent(
 *     val orderId: String,
 *     val customerId: String
 * )
 * ```
 *
 * ### With Static Context
 * ```kotlin
 * @OutboxEvent(
 *     key = "#this.userId",
 *     context = [
 *         OutboxContextEntry(key = "eventType", value = "'USER_REGISTERED'"),
 *         OutboxContextEntry(key = "version", value = "'1.0'")
 *     ]
 * )
 * data class UserRegisteredEvent(
 *     val userId: String,
 *     val email: String
 * )
 * ```
 *
 * ### With Dynamic Context Using SpEL
 * ```kotlin
 * @OutboxEvent(
 *     key = "#this.orderId",
 *     context = [
 *         OutboxContextEntry(key = "customerId", value = "#this.customerId"),
 *         OutboxContextEntry(key = "orderTotal", value = "#this.total.toString()"),
 *         OutboxContextEntry(key = "region", value = "#this.shippingAddress.region")
 *     ]
 * )
 * data class OrderConfirmedEvent(
 *     val orderId: String,
 *     val customerId: String,
 *     val total: BigDecimal,
 *     val shippingAddress: Address
 * )
 * ```
 *
 * @param key Optional SpEL expression to extract the record key from the event. Must evaluate to a String value.
 * @param context Optional array of context entries to be added to the outbox record. Each entry contains a key-value
 *                pair where the value can be a SpEL expression evaluated against the event payload. Context metadata
 *                is persisted with the outbox record and can be used for tracing, debugging, or filtering.
 *                **Use this for event-specific context** that is unique to this particular payload type
 *                (e.g., customerId, orderTotal, region extracted from the event itself).
 *                **For global context** that should be added to all outbox records regardless of payload type
 *                (e.g., traceId, spanId, tenantId, correlationId), implement the [io.namastack.outbox.context.OutboxContextProvider]
 *                interface instead. This avoids repeating the same context entries across all event annotations.
 *
 * @author Roland Beisel, Aleksander Zamojski
 * @since 0.3.0
 * @see io.namastack.outbox.context.OutboxContextProvider
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class OutboxEvent(
    val key: String = "",
    val context: Array<OutboxContextEntry> = [],
) {
    /**
     * Defines a key-value pair to be added to the outbox record context.
     *
     * Context entries provide metadata that is persisted alongside the outbox record.
     * The value can be a static string or a SpEL expression that is evaluated against
     * the event payload at runtime.
     *
     * @param key The context key (e.g., "correlationId", "source", "eventType")
     * @param value The context value. Can be a literal string or a SpEL expression
     *              (e.g., "#this.userId", "#root.correlationId", "payment-service")
     */
    @Retention(AnnotationRetention.RUNTIME)
    @Target()
    annotation class OutboxContextEntry(
        val key: String,
        val value: String,
    )
}
