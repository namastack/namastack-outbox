package io.namastack.outbox.retry

import java.time.Duration
import kotlin.math.pow

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
class ExponentialBackoffDelayCalculator(
    private val initialDelay: Duration,
    private val maxDelay: Duration,
    private val backoffMultiplier: Double,
) : OutboxDelayCalculator {
    /**
     * Calculates the next delay using exponential backoff.
     *
     * The delay is calculated as: initialDelay * (backoffMultiplier ^ failureCount)
     * The result is capped at the maximum delay.
     *
     * @param failureCount The number of failures that have occurred
     * @return Calculated delay duration, capped at maxDelay
     */
    override fun calculate(failureCount: Int): Duration {
        val delayMillis = (initialDelay.toMillis() * backoffMultiplier.pow(failureCount - 1)).toLong()

        return Duration.ofMillis(minOf(delayMillis, maxDelay.toMillis()))
    }

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
        private var jitter: Duration? = null

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
         * Builds the ExponentialBackoffRetryPolicy with configured parameters.
         *
         * If jitter is configured, wraps the policy in a JitteredRetryPolicy.
         *
         * @return Configured ExponentialBackoffRetryPolicy (or JitteredRetryPolicy if jitter is set)
         * @throws IllegalStateException if both includeExceptions and excludeExceptions are set
         */
        fun build(): OutboxDelayCalculator {
            val basePolicy =
                ExponentialBackoffDelayCalculator(
                    initialDelay = initialDelay,
                    maxDelay = maxDelay,
                    backoffMultiplier = backoffMultiplier,
                )

            return if (jitter != null) {
                JitteredDelayCalculator(basePolicy, jitter!!)
            } else {
                basePolicy
            }
        }
    }
}
