package io.namastack.outbox.annotation

/**
 * Specifies a custom retry policy for an outbox handler method.
 *
 * The value must be the name of a Spring bean implementing [io.namastack.outbox.retry.OutboxRetryPolicy].
 *
 * ## Example
 * ```kotlin
 * @Component
 * class PaymentHandler {
 *     @OutboxHandler
 *     @OutboxRetryable("aggressiveRetryPolicy")
 *     fun handlePayment(event: PaymentEvent) {
 *         // Uses aggressiveRetryPolicy bean
 *     }
 * }
 * ```
 *
 * @property value Spring bean name of the OutboxRetryPolicy to use
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class OutboxRetryable(
    val value: String,
)
