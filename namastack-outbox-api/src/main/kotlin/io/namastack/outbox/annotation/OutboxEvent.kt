package io.namastack.outbox.annotation

/**
 * Marks a payload class for automatic outbox persistence.
 *
 * When a payload annotated with @OutboxEvent is published via Spring's ApplicationEventPublisher,
 * it will be automatically persisted to the outbox database before being delivered to listeners.
 *
 * ## Context Providers
 *
 * By default, all registered [io.namastack.outbox.context.OutboxContextProvider] beans contribute context.
 * Use [contextProviders] to limit which providers are used for this specific payload type.
 *
 * ## Example
 *
 * ```kotlin
 * @OutboxEvent(
 *     key = "#orderId",
 *     contextProviders = ["tracingProvider", "tenantProvider"]
 * )
 * data class OrderCreated(val orderId: String, val amount: BigDecimal)
 * ```
 *
 * @param key Optional SpEL expression to extract the record key from the payload. Must evaluate to a String value.
 * @param contextProviders Optional list of context provider bean names to use. If empty, all providers are used.
 *
 * @author Roland Beisel
 * @since 0.3.0
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class OutboxEvent(
    val key: String = "",
    val contextProviders: Array<String> = [],
)
