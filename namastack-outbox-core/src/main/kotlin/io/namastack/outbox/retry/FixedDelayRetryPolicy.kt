package io.namastack.outbox.retry

import java.time.Duration
import kotlin.reflect.KClass

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
class FixedDelayRetryPolicy(
    private val delay: Duration,
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
     * Returns the fixed delay duration for all retry attempts.
     *
     * @param failureCount The number of failures that have occurred (ignored)
     * @return The fixed delay duration
     */
    override fun nextDelay(failureCount: Int): Duration = delay

    /**
     * Returns the maximum number of retry attempts.
     *
     * @return Maximum retry attempts configured for this policy
     */
    override fun maxRetries(): Int = maxRetries

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
        private var maxRetries: Int = 3
        private var jitter: Duration? = null
        private val includeExceptions = mutableSetOf<KClass<out Throwable>>()
        private val excludeExceptions = mutableSetOf<KClass<out Throwable>>()

        /**
         * Sets the fixed delay between retries.
         *
         * @param delay Delay duration
         * @return This builder for chaining
         */
        fun delay(delay: Duration): Builder = apply { this.delay = delay }

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
         * The actual delay will be the fixed delay ± jitter (randomly).
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
         * Builds the FixedDelayRetryPolicy with configured parameters.
         *
         * If jitter is configured, wraps the policy in a JitteredRetryPolicy.
         *
         * @return Configured FixedDelayRetryPolicy (or JitteredRetryPolicy if jitter is set)
         * @throws IllegalStateException if both includeExceptions and excludeExceptions are set
         */
        fun build(): OutboxRetryPolicy {
            require(includeExceptions.isEmpty() || excludeExceptions.isEmpty()) {
                "Cannot specify both includeExceptions and excludeExceptions. Use one or the other."
            }

            val basePolicy =
                FixedDelayRetryPolicy(
                    delay = delay,
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
