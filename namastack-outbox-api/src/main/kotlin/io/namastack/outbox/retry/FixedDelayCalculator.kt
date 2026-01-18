package io.namastack.outbox.retry

import java.time.Duration

/**
 * Retry policy that uses a fixed delay between retry attempts.
 *
 * This policy always waits the same amount of time between retries,
 * regardless of the retry count or exception type.
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
 * FixedDelayRetryPolicy(
 *     delay = Duration.ofSeconds(5),
 *     includeExceptions = setOf(SocketTimeoutException::class, IOException::class),
 * )
 * ```
 *
 * Retry everything except validation errors:
 * ```kotlin
 * FixedDelayRetryPolicy(
 *     delay = Duration.ofSeconds(5),
 *     excludeExceptions = setOf(IllegalArgumentException::class, ValidationException::class),
 * )
 * ```
 *
 * @param delay Fixed delay duration between retries
 * @param maxRetries Maximum number of retry attempts before marking as failed
 * @param includeExceptions Only retry these exception types (whitelist). Empty = disabled.
 * @param excludeExceptions Never retry these exception types (blacklist). Empty = disabled.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
class FixedDelayCalculator(
    private val delay: Duration,
) : OutboxDelayCalculator {
    /**
     * Returns the fixed delay duration for all retry attempts.
     *
     * @param failureCount The number of failures that have occurred (ignored)
     * @return The fixed delay duration
     */
    override fun calculate(failureCount: Int): Duration = delay

    companion object {
        /**
         * Creates a builder for constructing a FixedDelayRetryPolicy with fluent API.
         *
         * ## Example: Basic Configuration
         * ```kotlin
         * val policy = FixedDelayRetryPolicy.builder()
         *     .delay(Duration.ofSeconds(5))
         *     .maxRetries(3)
         *     .build()
         * ```
         *
         * ## Example: With Jitter
         * ```kotlin
         * val policy = FixedDelayRetryPolicy.builder()
         *     .delay(Duration.ofSeconds(5))
         *     .maxRetries(3)
         *     .jitter(Duration.ofMillis(500))
         *     .build()
         * ```
         *
         * ## Example: With Exception Filtering (Whitelist)
         * ```kotlin
         * val policy = FixedDelayRetryPolicy.builder()
         *     .delay(Duration.ofSeconds(5))
         *     .maxRetries(3)
         *     .includeException(SocketTimeoutException::class)
         *     .includeException(IOException::class)
         *     .build()
         * ```
         *
         * ## Example: With Exception Filtering (Blacklist)
         * ```kotlin
         * val policy = FixedDelayRetryPolicy.builder()
         *     .delay(Duration.ofSeconds(5))
         *     .maxRetries(3)
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
     * Builder for constructing FixedDelayRetryPolicy with fluent API.
     */
    class Builder {
        private var delay: Duration = Duration.ofSeconds(5)
        private var jitter: Duration? = null

        /**
         * Sets the fixed delay between retries.
         *
         * @param delay Delay duration
         * @return This builder for chaining
         */
        fun delay(delay: Duration): Builder = apply { this.delay = delay }

        /**
         * Sets the jitter duration to add randomness to retry delays.
         *
         * Jitter helps prevent thundering herd problems by adding random variance to delays.
         * The actual delay will be the fixed delay ± jitter (randomly).
         *
         * @param jitter Maximum random jitter to add/subtract from delays
         * @return This builder for chaining
         */
        fun jitter(jitter: Duration): Builder = apply { this.jitter = jitter }

        /**
         * Builds the FixedDelayRetryPolicy with configured parameters.
         *
         * If jitter is configured, wraps the policy in a JitteredRetryPolicy.
         *
         * @return Configured FixedDelayRetryPolicy (or JitteredRetryPolicy if jitter is set)
         * @throws IllegalStateException if both includeExceptions and excludeExceptions are set
         */
        fun build(): OutboxDelayCalculator {
            val basePolicy =
                FixedDelayCalculator(
                    delay = delay,
                )

            return if (jitter != null) {
                JitteredDelayCalculator(basePolicy, jitter!!)
            } else {
                basePolicy
            }
        }
    }
}
