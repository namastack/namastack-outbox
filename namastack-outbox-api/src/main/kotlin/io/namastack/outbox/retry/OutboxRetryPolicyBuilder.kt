package io.namastack.outbox.retry

import java.time.Duration
import kotlin.reflect.KClass

/**
 * Builder for creating [OutboxRetryPolicy] instances with a fluent API.
 *
 * ## Example
 * ```kotlin
 * val policy = OutboxRetryPolicy.builder()
 *     .maxRetries(5)
 *     .backOff(BackOffStrategies.exponential(Duration.ofSeconds(1), 2.0))
 *     .retryOn(IOException::class)
 *     .withJitter(0.1)
 *     .build()
 * ```
 *
 * @author Roland Beisel
 * @since 1.0.0-RC2
 */
class OutboxRetryPolicyBuilder {
    private var maxRetries: Int = 3
    private var backOffStrategy: BackOffStrategy = BackOffStrategy { Duration.ofSeconds(5) }
    private var retryableExceptions: MutableSet<KClass<out Throwable>> = mutableSetOf()
    private var nonRetryableExceptions: MutableSet<KClass<out Throwable>> = mutableSetOf()
    private var retryPredicate: ((Throwable) -> Boolean)? = null
    private var jitterFactor: Double? = null
    private var jitterDuration: Duration? = null

    /**
     * Sets the maximum number of retry attempts.
     *
     * @param max Maximum number of retries (must be >= 0)
     * @return This builder for method chaining
     */
    fun maxRetries(max: Int): OutboxRetryPolicyBuilder {
        require(max >= 0) { "maxRetries must be non-negative" }

        this.maxRetries = max

        return this
    }

    /**
     * Sets the backoff strategy for calculating delays between retries.
     *
     * @param strategy The backoff strategy to use
     * @return This builder for method chaining
     * @see io.namastack.outbox.retry.BackOffStrategy
     */
    fun backOff(strategy: BackOffStrategy): OutboxRetryPolicyBuilder {
        this.backOffStrategy = strategy

        return this
    }

    /**
     * Specifies exception types that should trigger retries (whitelist).
     *
     * When set, only exceptions matching these types (or their subclasses)
     * will be retried. All other exceptions will not trigger a retry.
     *
     * Note: If [retryIf] is set, it takes precedence over this setting.
     *
     * @param exceptions Exception types to retry
     * @return This builder for method chaining
     */
    fun retryOn(vararg exceptions: KClass<out Throwable>): OutboxRetryPolicyBuilder {
        this.retryableExceptions.addAll(exceptions)

        return this
    }

    /**
     * Specifies exception types that should not trigger retries (blacklist).
     *
     * When set, exceptions matching these types (or their subclasses)
     * will not be retried. All other exceptions will trigger a retry.
     *
     * Note: If [retryOn] is set, it takes precedence over this setting.
     * Note: If [retryIf] is set, it takes precedence over this setting.
     *
     * @param exceptions Exception types to not retry
     * @return This builder for method chaining
     */
    fun noRetryOn(vararg exceptions: KClass<out Throwable>): OutboxRetryPolicyBuilder {
        this.nonRetryableExceptions.addAll(exceptions)

        return this
    }

    /**
     * Sets a custom predicate for determining whether to retry.
     *
     * When set, this predicate takes precedence over [retryOn] and [noRetryOn].
     *
     * @param predicate Function that returns true if the exception should trigger a retry
     * @return This builder for method chaining
     */
    fun retryIf(predicate: (Throwable) -> Boolean): OutboxRetryPolicyBuilder {
        this.retryPredicate = predicate

        return this
    }

    /**
     * Adds random jitter to the backoff delay using a factor (percentage).
     *
     * Jitter helps prevent thundering herd problems when multiple instances
     * retry at approximately the same time. The jitter is applied as:
     * `baseDelay ± (baseDelay * jitterFactor)`
     *
     * ## Example
     * ```kotlin
     * val policy = OutboxRetryPolicy.builder()
     *     .maxRetries(5)
     *     .backOff(BackOffStrategies.exponential(Duration.ofSeconds(1), 2.0))
     *     .withJitter(0.15) // Add 15% jitter
     *     .build()
     * ```
     *
     * @param factor Percentage of jitter to apply (0.0 to 1.0, default: 0.1 = 10%)
     * @return This builder for method chaining
     */
    fun withJitter(factor: Double = 0.1): OutboxRetryPolicyBuilder {
        require(factor in 0.0..1.0) { "jitterFactor must be between 0.0 and 1.0" }
        this.jitterFactor = factor
        this.jitterDuration = null

        return this
    }

    /**
     * Adds random jitter to the backoff delay using a fixed maximum duration.
     *
     * Jitter helps prevent thundering herd problems when multiple instances
     * retry at approximately the same time. A random value between 0 and the
     * specified duration is added to each delay.
     *
     * ## Example
     * ```kotlin
     * val policy = OutboxRetryPolicy.builder()
     *     .maxRetries(5)
     *     .backOff(BackOffStrategies.fixed(Duration.ofSeconds(5)))
     *     .withJitter(Duration.ofMillis(500)) // Add 0-500ms random jitter
     *     .build()
     * ```
     *
     * @param maxJitter Maximum jitter duration to add
     * @return This builder for method chaining
     */
    fun withJitter(maxJitter: Duration): OutboxRetryPolicyBuilder {
        require(!maxJitter.isNegative) { "maxJitter must not be negative" }

        this.jitterDuration = maxJitter
        this.jitterFactor = null

        return this
    }

    /**
     * Builds the [OutboxRetryPolicy] with the configured settings.
     *
     * @return A new OutboxRetryPolicy instance
     */
    fun build(): OutboxRetryPolicy {
        val finalMaxRetries = maxRetries
        val finalBackOffStrategy = backOffStrategy
        val finalRetryableExceptions = retryableExceptions.toSet()
        val finalNonRetryableExceptions = nonRetryableExceptions.toSet()
        val finalRetryPredicate = retryPredicate
        val finalJitterFactor = jitterFactor
        val finalJitterDuration = jitterDuration

        return object : OutboxRetryPolicy {
            override fun shouldRetry(exception: Throwable): Boolean {
                finalRetryPredicate?.let { return it(exception) }

                if (finalNonRetryableExceptions.any { it.isInstance(exception) }) {
                    return false
                }

                if (finalRetryableExceptions.isNotEmpty()) {
                    return finalRetryableExceptions.any { it.isInstance(exception) }
                }

                return true
            }

            override fun nextDelay(failureCount: Int): Duration {
                val baseDelay = finalBackOffStrategy.nextDelay(failureCount)

                if (finalJitterDuration != null) {
                    val jitterMillis = (Math.random() * finalJitterDuration.toMillis()).toLong()

                    return baseDelay.plusMillis(jitterMillis)
                }

                if (finalJitterFactor != null && finalJitterFactor != 0.0) {
                    val jitterRange = baseDelay.toMillis() * finalJitterFactor
                    val jitter = (Math.random() * 2 - 1) * jitterRange
                    val finalDelayMillis = (baseDelay.toMillis() + jitter).toLong().coerceAtLeast(0)

                    return Duration.ofMillis(finalDelayMillis)
                }

                return baseDelay
            }

            override fun maxRetries(): Int = finalMaxRetries
        }
    }
}
