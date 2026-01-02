package io.namastack.outbox.retry

/**
 * Interface for handlers that want to specify their retry policy programmatically.
 *
 * Provides a type-safe alternative to [io.namastack.outbox.annotation.OutboxRetryable].
 *
 * ## Example
 *
 * ```kotlin
 * @Component
 * class OrderHandler(
 *     private val customPolicy: OutboxRetryPolicy
 * ) : OutboxTypedHandler<OrderEvent>, OutboxRetryAware {
 *     override fun handle(payload: OrderEvent) { /* ... */ }
 *     override fun getRetryPolicy() = customPolicy
 * }
 * ```
 *
 * @author Roland Beisel
 * @since 1.0.0
 */
interface OutboxRetryAware {
    /**
     * Returns the retry policy for this handler.
     * Called once during handler registration, result is cached.
     */
    fun getRetryPolicy(): OutboxRetryPolicy
}
