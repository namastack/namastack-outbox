package io.namastack.outbox.annotation

import io.namastack.outbox.retry.OutboxRetryPolicy
import kotlin.reflect.KClass

/**
 * Specifies a custom retry policy for an outbox handler method.
 *
 * Supports two ways to specify the retry policy:
 *
 * ## Option 1: By Bean Name (string-based)
 * ```kotlin
 * @OutboxHandler
 * @OutboxRetryable(name = "aggressiveRetryPolicy")
 * fun handlePayment(event: PaymentEvent) { }
 * ```
 *
 * ## Option 2: By Class (type-safe, recommended)
 * ```kotlin
 * @OutboxHandler
 * @OutboxRetryable(AggressiveRetryPolicy::class)
 * fun handlePayment(event: PaymentEvent) { }
 * ```
 *
 * The class-based approach provides:
 * - Compile-time type safety
 * - IDE auto-completion and navigation
 * - Refactoring-safe references
 *
 * @property value The retry policy class to use (mutually exclusive with [name])
 * @property name The Spring bean name of the retry policy (mutually exclusive with [value])
 *
 * @author Roland Beisel
 * @since 0.5.0
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class OutboxRetryable(
    /**
     * The retry policy class to use.
     *
     * The policy bean will be looked up by type from the Spring context.
     * Mutually exclusive with [name].
     *
     * Default value indicates no class-based lookup should be performed.
     */
    val value: KClass<out OutboxRetryPolicy> = OutboxRetryPolicy::class,
    /**
     * Spring bean name of the retry policy.
     *
     * The policy bean will be looked up by name from the Spring context.
     * Mutually exclusive with [value].
     *
     * Default empty string indicates no name-based lookup should be performed.
     */
    val name: String = "",
)
