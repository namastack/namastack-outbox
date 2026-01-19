package io.namastack.outbox.retry

import java.time.Duration
import java.util.function.Predicate
import kotlin.math.pow

/**
 * Interface for retry policies that determine when and how to retry failed outbox record processing.
 *
 * Implementations define the retry behavior including whether to retry based on the exception
 * and what delay to use between retry attempts.
 *
 * ## Usage Example
 *
 * ```kotlin
 * // Create a retry policy with fixed delay
 * val fixedDelayPolicy = OutboxRetryPolicy.builder()
 *     .maxRetries(5)
 *     .fixedBackOff(Duration.ofSeconds(30))
 *     .build()
 *
 * // Create a retry policy with linear backoff
 * val linearBackoffPolicy = OutboxRetryPolicy.builder()
 *     .maxRetries(10)
 *     .linearBackoff(
 *         initialDelay = Duration.ofSeconds(5),
 *         increment = Duration.ofSeconds(5),
 *         maxDelay = Duration.ofMinutes(2)
 *     )
 *     .build()
 *
 * // Create a retry policy with exponential backoff
 * val exponentialBackoffPolicy = OutboxRetryPolicy.builder()
 *     .maxRetries(10)
 *     .exponentialBackoff(
 *         initialDelay = Duration.ofSeconds(10),
 *         maxDelay = Duration.ofMinutes(5),
 *         multiplier = 2.0
 *     )
 *     .build()
 *
 * // Create a retry policy with custom retry predicate
 * val customPolicy = OutboxRetryPolicy.builder()
 *     .maxRetries(3)
 *     .fixedBackOff(Duration.ofSeconds(15))
 *     .jitter(Duration.ofSeconds(5))
 *     .retryIf { exception ->
 *         exception is RetryableException
 *     }
 *     .build()
 *
 * // Create a retry policy with custom BackOffStrategy
 * val customDelayPolicy = OutboxRetryPolicy.builder()
 *     .backOff(myCustomBackOffStrategy)
 *     .maxRetries(7)
 *     .build()
 *
 * // Create a retry policy with specific exceptions
 * val exceptionBasedPolicy = OutboxRetryPolicy.builder()
 *     .maxRetries(5)
 *     .fixedBackOff(Duration.ofSeconds(10))
 *     .retryOn(IOException::class.java, TimeoutException::class.java)
 *     .noRetryOn(IllegalArgumentException::class.java)
 *     .build()
 * ```
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
interface OutboxRetryPolicy {
    /**
     * Determines whether a retry should be attempted based on the exception.
     *
     * @param exception The exception that occurred during processing
     * @return True if a retry should be attempted, false otherwise
     */
    fun shouldRetry(exception: Throwable): Boolean

    /**
     * Calculates the delay before the next retry attempt.
     *
     * @param failureCount The number of failures occurred (starting from 1)
     * @return Duration to wait before the next retry attempt
     */
    fun nextDelay(failureCount: Int): Duration

    /**
     * Returns the maximum number of retry attempts allowed.
     *
     * After this many failures, the record will be marked as FAILED
     * and no further retry attempts will be made.
     *
     * @return Maximum number of retry attempts
     */
    fun maxRetries(): Int

    companion object {
        @JvmStatic
        fun builder(): Builder = Builder()

        private fun assertIsNotNegative(
            name: String,
            duration: Duration,
        ) {
            assertIsNotNegative(name, !duration.isNegative)
        }

        private fun assertIsNotNegative(
            name: String,
            value: Boolean,
        ) {
            require(value) {
                "Invalid $name: must be greater than or equal to zero."
            }
        }

        private fun assertIsPositive(
            name: String,
            value: Int,
        ) {
            assertIsPositive(name, value > 0)
        }

        private fun assertIsPositive(
            name: String,
            duration: Duration,
        ) {
            assertIsPositive(name, !duration.isNegative && !duration.isZero)
        }

        private fun assertIsPositive(
            name: String,
            value: Boolean,
        ) {
            require(value) {
                "Invalid $name: must be greater than zero."
            }
        }
    }

    /**
     * Builder for constructing [OutboxRetryPolicy] instances.
     *
     * Provides a fluent API for configuring retry behavior including:
     * - Maximum number of retry attempts
     * - Backoff strategy (fixed, linear, exponential, or custom)
     * - Jitter to randomize delays
     * - Exception-based retry rules
     * - Custom retry predicates
     *
     * Default configuration:
     * - Max retries: 3
     * - Backoff strategy: Fixed delay of 5 seconds
     * - Jitter: None
     * - Retry on all exceptions
     *
     * @author Aleksander Zamojski
     * @since 1.0.0-RC2
     */
    class Builder private constructor(
        private val maxRetries: Int,
        private val backOffStrategy: BackOffStrategy,
        private val jitter: Duration,
        private var retryableExceptions: Collection<Class<out Throwable>>,
        private var nonRetryableExceptions: Collection<Class<out Throwable>>,
        private val retryPredicate: Predicate<Throwable>?,
    ) {
        constructor() : this(
            maxRetries = 3,
            backOffStrategy = FixedBackOffStrategy(Duration.ofSeconds(5)),
            jitter = Duration.ZERO,
            retryableExceptions = emptyList(),
            nonRetryableExceptions = emptyList(),
            retryPredicate = null,
        )

        /**
         * Sets the maximum number of retry attempts.
         *
         * After this many failures, the outbox record will be marked as FAILED
         * and no further retry attempts will be made.
         *
         * @param maxRetries Maximum number of retry attempts (must be positive)
         * @return Builder instance for method chaining
         * @throws IllegalArgumentException if maxRetries is not positive
         */
        fun maxRetries(maxRetries: Int): Builder {
            assertIsPositive("maxRetries", maxRetries)

            return Builder(
                maxRetries = maxRetries,
                backOffStrategy = backOffStrategy,
                jitter = jitter,
                retryableExceptions = retryableExceptions,
                nonRetryableExceptions = nonRetryableExceptions,
                retryPredicate = retryPredicate,
            )
        }

        /**
         * Configures a fixed delay backoff strategy.
         *
         * The same delay will be used between all retry attempts.
         *
         * @param delay The fixed delay duration between retries (must be positive)
         * @return Builder instance for method chaining
         * @throws IllegalArgumentException if delay is not positive
         */
        fun fixedBackOff(delay: Duration): Builder {
            assertIsPositive("delay", delay)

            return backOff(
                strategy = FixedBackOffStrategy(delay = delay),
            )
        }

        /**
         * Configures a linear backoff strategy.
         *
         * The delay increases linearly with each retry attempt:
         * - Retry 1: initialDelay
         * - Retry 2: initialDelay + increment
         * - Retry 3: initialDelay + 2 * increment
         * - And so on, up to maxDelay
         *
         * @param initialDelay The initial delay duration (must be positive)
         * @param increment The amount to increase delay by for each retry (must be positive)
         * @param maxDelay The maximum delay duration (must be positive)
         * @return Builder instance for method chaining
         * @throws IllegalArgumentException if any parameter is not positive
         */
        fun linearBackoff(
            initialDelay: Duration,
            increment: Duration,
            maxDelay: Duration,
        ): Builder {
            assertIsPositive("initialDelay", initialDelay)
            assertIsPositive("increment", increment)
            assertIsPositive("maxDelay", maxDelay)

            return backOff(
                strategy =
                    LinearBackOffStrategy(
                        initialDelay = initialDelay,
                        increment = increment,
                        maxDelay = maxDelay,
                    ),
            )
        }

        /**
         * Configures an exponential backoff strategy.
         *
         * The delay increases exponentially with each retry attempt:
         * - Retry 1: initialDelay
         * - Retry 2: initialDelay * multiplier
         * - Retry 3: initialDelay * multiplier^2
         * - And so on, up to maxDelay
         *
         * @param initialDelay The initial delay duration (must be positive)
         * @param multiplier The multiplication factor for exponential growth (must be greater than 1.0)
         * @param maxDelay The maximum delay duration (must be positive)
         * @return Builder instance for method chaining
         * @throws IllegalArgumentException if initialDelay or maxDelay is not positive, or if multiplier is not greater than 1.0
         */
        fun exponentialBackoff(
            initialDelay: Duration,
            multiplier: Double,
            maxDelay: Duration,
        ): Builder {
            assertIsPositive("initialDelay", initialDelay)
            require(multiplier > 1.0) { "Invalid multiplier: must be greater than 1.0." }
            assertIsPositive("maxDelay", maxDelay)

            return backOff(
                strategy =
                    ExponentialBackOffStrategy(
                        initialDelay = initialDelay,
                        multiplier = multiplier,
                        maxDelay = maxDelay,
                    ),
            )
        }

        /**
         * Configures a custom backoff strategy.
         *
         * Allows using a custom implementation of [BackOffStrategy] to calculate retry delays.
         *
         * @param strategy The custom backoff strategy
         * @return Builder instance for method chaining
         */
        fun backOff(strategy: BackOffStrategy): Builder =
            Builder(
                maxRetries = maxRetries,
                backOffStrategy = strategy,
                jitter = jitter,
                retryableExceptions = retryableExceptions,
                nonRetryableExceptions = nonRetryableExceptions,
                retryPredicate = retryPredicate,
            )

        /**
         * Configures jitter to randomize retry delays.
         *
         * Jitter adds randomness to the calculated delay by varying it within the range
         * [baseDelay - jitter, baseDelay + jitter]. This helps prevent the thundering herd
         * problem when multiple instances retry at the same time.
         *
         * @param jitter The maximum amount of time to randomly add or subtract from the base delay (must not be negative)
         * @return Builder instance for method chaining
         * @throws IllegalArgumentException if jitter is negative
         */
        fun jitter(jitter: Duration): Builder {
            assertIsNotNegative("jitter", jitter)

            return Builder(
                maxRetries = maxRetries,
                backOffStrategy = backOffStrategy,
                jitter = jitter,
                retryableExceptions = retryableExceptions,
                nonRetryableExceptions = nonRetryableExceptions,
                retryPredicate = retryPredicate,
            )
        }

        /**
         * Specifies exception types that should trigger a retry.
         *
         * When specified, only exceptions of these types (or their subclasses) will be retried,
         * unless overridden by [noRetryOn] or a custom [retryIf] predicate.
         *
         * @param exceptions The exception types that should trigger a retry
         * @return Builder instance for method chaining
         */
        fun retryOn(vararg exceptions: Class<out Throwable>): Builder =
            retryOn(
                exceptions = exceptions.toList(),
            )

        /**
         * Specifies exception types that should trigger a retry.
         *
         * When specified, only exceptions of these types (or their subclasses) will be retried,
         * unless overridden by [noRetryOn] or a custom [retryIf] predicate.
         *
         * @param exceptions Collection of exception types that should trigger a retry
         * @return Builder instance for method chaining
         */
        fun retryOn(exceptions: Collection<Class<out Throwable>>): Builder =
            Builder(
                maxRetries = maxRetries,
                backOffStrategy = backOffStrategy,
                jitter = jitter,
                retryableExceptions = exceptions,
                nonRetryableExceptions = nonRetryableExceptions,
                retryPredicate = retryPredicate,
            )

        /**
         * Specifies exception types that should never trigger a retry.
         *
         * Exceptions of these types (or their subclasses) will not be retried,
         * even if they match [retryOn] rules or custom [retryIf] predicates.
         *
         * @param exceptions The exception types that should never trigger a retry
         * @return Builder instance for method chaining
         */
        fun noRetryOn(vararg exceptions: Class<out Throwable>): Builder =
            noRetryOn(
                exceptions = exceptions.toList(),
            )

        /**
         * Specifies exception types that should never trigger a retry.
         *
         * Exceptions of these types (or their subclasses) will not be retried,
         * even if they match [retryOn] rules or custom [retryIf] predicates.
         *
         * @param exceptions Collection of exception types that should never trigger a retry
         * @return Builder instance for method chaining
         */
        fun noRetryOn(exceptions: Collection<Class<out Throwable>>): Builder =
            Builder(
                maxRetries = maxRetries,
                backOffStrategy = backOffStrategy,
                jitter = jitter,
                retryableExceptions = retryableExceptions,
                nonRetryableExceptions = exceptions,
                retryPredicate = retryPredicate,
            )

        /**
         * Configures a custom predicate for determining whether to retry based on an exception.
         *
         * The predicate receives the exception and returns true if a retry should be attempted.
         * This is evaluated after [noRetryOn] exceptions are checked but can be combined with [retryOn].
         *
         * @param predicate The predicate function to evaluate exceptions
         * @return Builder instance for method chaining
         */
        fun retryIf(predicate: Predicate<Throwable>): Builder =
            Builder(
                maxRetries = maxRetries,
                backOffStrategy = backOffStrategy,
                jitter = jitter,
                retryableExceptions = retryableExceptions,
                nonRetryableExceptions = nonRetryableExceptions,
                retryPredicate = predicate,
            )

        /**
         * Builds and returns a configured [OutboxRetryPolicy] instance.
         *
         * The policy will use the configured backoff strategy, retry rules, and maximum retry attempts
         * to determine retry behavior for failed outbox record processing.
         *
         * @return A new OutboxRetryPolicy instance with the configured behavior
         */
        fun build(): OutboxRetryPolicy =
            object : OutboxRetryPolicy {
                override fun shouldRetry(exception: Throwable): Boolean {
                    // If a non-retryable exception matches, do not retry
                    if (nonRetryableExceptions.any { it.isInstance(exception) }) {
                        return false
                    }

                    // retry if no explicit rules are defined
                    if (retryableExceptions.isEmpty() && retryPredicate == null) {
                        return true
                    }

                    // If a retryable exception matches, retry
                    if (retryableExceptions.any { it.isInstance(exception) }) {
                        return true
                    }

                    // If a retry predicate is defined and matches, retry
                    if (retryPredicate != null && retryPredicate.test(exception)) {
                        return true
                    }

                    return false
                }

                override fun nextDelay(failureCount: Int): Duration {
                    val baseDelay = backOffStrategy.nextDelay(failureCount)

                    return if (!jitter.isNegative && !jitter.isZero) {
                        applyJitter(baseDelay)
                    } else {
                        baseDelay
                    }
                }

                private fun applyJitter(baseDelay: Duration): Duration {
                    val min = maxOf(baseDelay.minus(jitter).toMillis(), 0)
                    val max = baseDelay.plus(jitter).toMillis()
                    val jitteredDelayMillis = min + (Math.random() * (max - min)).toLong()

                    return Duration.ofMillis(jitteredDelayMillis)
                }

                override fun maxRetries(): Int = maxRetries
            }
    }

    private class FixedBackOffStrategy(
        private val delay: Duration,
    ) : BackOffStrategy {
        override fun nextDelay(failureCount: Int): Duration = delay
    }

    private class LinearBackOffStrategy(
        private val initialDelay: Duration,
        private val increment: Duration,
        private val maxDelay: Duration,
    ) : BackOffStrategy {
        override fun nextDelay(failureCount: Int): Duration {
            val delayMillis = initialDelay.toMillis() + increment.toMillis() * (failureCount - 1)

            return Duration.ofMillis(minOf(delayMillis, maxDelay.toMillis()))
        }
    }

    private class ExponentialBackOffStrategy(
        private val initialDelay: Duration,
        private val multiplier: Double,
        private val maxDelay: Duration,
    ) : BackOffStrategy {
        override fun nextDelay(failureCount: Int): Duration {
            val delayMillis = (initialDelay.toMillis() * multiplier.pow(failureCount - 1)).toLong()

            return Duration.ofMillis(minOf(delayMillis, maxDelay.toMillis()))
        }
    }
}
