package io.namastack.outbox.retry

import java.time.Duration
import kotlin.math.pow
import kotlin.reflect.KClass

/**
 * Retry policy that implements exponential backoff with configurable parameters.
 *
 * This policy increases the delay between retries exponentially, starting with
 * an initial delay and multiplying by a backoff multiplier for each retry,
 * up to a maximum delay.
 *
 * ## Exception Filtering
 *
 * You can control which exceptions should trigger retries using two mutually exclusive parameters:
 *
 * - **includeExceptions**: Only retry if exception matches one of these types (whitelist approach)
 * - **excludeExceptions**: Retry all exceptions except these types (blacklist approach)
 *
 * If both are empty, all exceptions trigger retries (default behavior).
 * If both are specified, includeExceptions takes precedence.
 *
 * ## Examples
 *
 * Retry only network errors:
 * ```kotlin
 * ExponentialBackoffRetryPolicy(
 *     includeExceptions = setOf(SocketTimeoutException::class, IOException::class),
 *     // ...
 * )
 * ```
 *
 * Retry everything except validation errors:
 * ```kotlin
 * ExponentialBackoffRetryPolicy(
 *     excludeExceptions = setOf(IllegalArgumentException::class, ValidationException::class),
 *     // ...
 * )
 * ```
 *
 * @param initialDelay Initial delay for the first retry
 * @param maxDelay Maximum delay between retries
 * @param backoffMultiplier Multiplier for exponential backoff calculation
 * @param maxRetries Maximum number of retry attempts before marking as failed
 * @param includeExceptions Only retry these exception types (whitelist). Empty = disabled.
 * @param excludeExceptions Never retry these exception types (blacklist). Empty = disabled.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
class ExponentialBackoffRetryPolicy(
    private val initialDelay: Duration,
    private val maxDelay: Duration,
    private val backoffMultiplier: Double,
    private val maxRetries: Int,
    private val includeExceptions: Set<KClass<out Throwable>> = emptySet(),
    private val excludeExceptions: Set<KClass<out Throwable>> = emptySet(),
) : OutboxRetryPolicy {
    /**
     * Determines if the exception should trigger a retry attempt.
     *
     * ## Resolution Logic
     *
     * 1. If includeExceptions is not empty: Retry only if exception matches one of the types
     * 2. If excludeExceptions is not empty: Retry if exception does NOT match any of the types
     * 3. If both are empty: Always retry (default behavior)
     *
     * Type matching includes subclass checking (e.g., IOException matches SocketTimeoutException).
     *
     * @param exception The exception that occurred
     * @return true if retry should be attempted, false otherwise
     */
    override fun shouldRetry(exception: Throwable): Boolean {
        if (includeExceptions.isNotEmpty()) {
            return includeExceptions.any { it.java.isInstance(exception) }
        }

        if (excludeExceptions.isNotEmpty()) {
            return excludeExceptions.none { it.java.isInstance(exception) }
        }

        return true
    }

    /**
     * Calculates the next delay using exponential backoff.
     *
     * The delay is calculated as: initialDelay * (backoffMultiplier ^ failureCount)
     * The result is capped at the maximum delay.
     *
     * @param failureCount The number of failures that have occurred
     * @return Calculated delay duration, capped at maxDelay
     */
    override fun nextDelay(failureCount: Int): Duration {
        val delayMillis = (initialDelay.toMillis() * backoffMultiplier.pow(failureCount - 1)).toLong()

        return Duration.ofMillis(minOf(delayMillis, maxDelay.toMillis()))
    }

    /**
     * Returns the maximum number of retry attempts.
     *
     * @return Maximum retry attempts configured for this policy
     */
    override fun maxRetries(): Int = maxRetries

    companion object {
        /**
         * Creates a builder for constructing an ExponentialBackoffRetryPolicy with fluent API.
         *
         * ## Example: Basic Configuration
         * ```kotlin
         * val policy = ExponentialBackoffRetryPolicy.builder()
         *     .initialDelay(Duration.ofSeconds(1))
         *     .maxDelay(Duration.ofMinutes(5))
         *     .backoffMultiplier(2.0)
         *     .maxRetries(5)
         *     .build()
         * ```
         *
         * ## Example: With Jitter
         * ```kotlin
         * val policy = ExponentialBackoffRetryPolicy.builder()
         *     .initialDelay(Duration.ofSeconds(1))
         *     .maxDelay(Duration.ofMinutes(5))
         *     .backoffMultiplier(2.0)
         *     .maxRetries(5)
         *     .jitter(Duration.ofMillis(500))
         *     .build()
         * ```
         *
         * ## Example: With Exception Filtering (Whitelist)
         * ```kotlin
         * val policy = ExponentialBackoffRetryPolicy.builder()
         *     .initialDelay(Duration.ofSeconds(1))
         *     .maxDelay(Duration.ofMinutes(5))
         *     .maxRetries(5)
         *     .includeException(SocketTimeoutException::class)
         *     .includeException(IOException::class)
         *     .build()
         * ```
         *
         * ## Example: With Exception Filtering (Blacklist)
         * ```kotlin
         * val policy = ExponentialBackoffRetryPolicy.builder()
         *     .initialDelay(Duration.ofSeconds(1))
         *     .maxDelay(Duration.ofMinutes(5))
         *     .maxRetries(5)
         *     .excludeException(IllegalArgumentException::class)
         *     .excludeException(ValidationException::class)
         *     .build()
         * ```
         *
         * @return A new Builder instance
         */
        fun builder(): Builder = Builder()
    }

    /**
     * Builder for constructing ExponentialBackoffRetryPolicy with fluent API.
     */
    class Builder {
        private var initialDelay: Duration = Duration.ofSeconds(1)
        private var maxDelay: Duration = Duration.ofMinutes(5)
        private var backoffMultiplier: Double = 2.0
        private var maxRetries: Int = 3
        private var jitter: Duration? = null
        private val includeExceptions = mutableSetOf<KClass<out Throwable>>()
        private val excludeExceptions = mutableSetOf<KClass<out Throwable>>()

        /**
         * Sets the initial delay for the first retry.
         *
         * @param delay Initial delay duration
         * @return This builder for chaining
         */
        fun initialDelay(delay: Duration): Builder = apply { this.initialDelay = delay }

        /**
         * Sets the maximum delay between retries.
         *
         * @param delay Maximum delay duration
         * @return This builder for chaining
         */
        fun maxDelay(delay: Duration): Builder = apply { this.maxDelay = delay }

        /**
         * Sets the backoff multiplier for exponential growth.
         *
         * @param multiplier Multiplier value (typically 2.0 or higher)
         * @return This builder for chaining
         */
        fun backoffMultiplier(multiplier: Double): Builder = apply { this.backoffMultiplier = multiplier }

        /**
         * Sets the maximum number of retry attempts.
         *
         * @param retries Maximum retry attempts
         * @return This builder for chaining
         */
        fun maxRetries(retries: Int): Builder = apply { this.maxRetries = retries }

        /**
         * Sets the jitter duration to add randomness to retry delays.
         *
         * Jitter helps prevent thundering herd problems by adding random variance to delays.
         * The actual delay will be the calculated delay ± jitter (randomly).
         *
         * @param jitter Maximum random jitter to add/subtract from delays
         * @return This builder for chaining
         */
        fun jitter(jitter: Duration): Builder = apply { this.jitter = jitter }

        /**
         * Adds an exception type to the include list (whitelist).
         *
         * Only exceptions matching these types will trigger retries.
         * Cannot be combined with excludeException.
         *
         * @param exceptionClass Exception class to include
         * @return This builder for chaining
         */
        fun includeException(exceptionClass: KClass<out Throwable>): Builder =
            apply {
                includeExceptions.add(exceptionClass)
            }

        /**
         * Adds multiple exception types to the include list (whitelist).
         *
         * @param exceptionClasses Exception classes to include
         * @return This builder for chaining
         */
        fun includeExceptions(vararg exceptionClasses: KClass<out Throwable>): Builder =
            apply {
                includeExceptions.addAll(exceptionClasses)
            }

        /**
         * Adds an exception type to the exclude list (blacklist).
         *
         * Exceptions matching these types will NOT trigger retries.
         * Cannot be combined with includeException.
         *
         * @param exceptionClass Exception class to exclude
         * @return This builder for chaining
         */
        fun excludeException(exceptionClass: KClass<out Throwable>): Builder =
            apply {
                excludeExceptions.add(exceptionClass)
            }

        /**
         * Adds multiple exception types to the exclude list (blacklist).
         *
         * @param exceptionClasses Exception classes to exclude
         * @return This builder for chaining
         */
        fun excludeExceptions(vararg exceptionClasses: KClass<out Throwable>): Builder =
            apply {
                excludeExceptions.addAll(exceptionClasses)
            }

        /**
         * Builds the ExponentialBackoffRetryPolicy with configured parameters.
         *
         * If jitter is configured, wraps the policy in a JitteredRetryPolicy.
         *
         * @return Configured ExponentialBackoffRetryPolicy (or JitteredRetryPolicy if jitter is set)
         * @throws IllegalStateException if both includeExceptions and excludeExceptions are set
         */
        fun build(): OutboxRetryPolicy {
            require(includeExceptions.isEmpty() || excludeExceptions.isEmpty()) {
                "Cannot specify both includeExceptions and excludeExceptions. Use one or the other."
            }

            val basePolicy =
                ExponentialBackoffRetryPolicy(
                    initialDelay = initialDelay,
                    maxDelay = maxDelay,
                    backoffMultiplier = backoffMultiplier,
                    maxRetries = maxRetries,
                    includeExceptions = includeExceptions.toSet(),
                    excludeExceptions = excludeExceptions.toSet(),
                )

            return if (jitter != null) {
                JitteredRetryPolicy(basePolicy, jitter!!)
            } else {
                basePolicy
            }
        }
    }
}
