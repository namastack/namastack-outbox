package io.namastack.outbox.retry

import java.time.Duration

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
 *     .fixedDelay(Duration.ofSeconds(30))
 *     .maxRetries(5)
 *     .build()
 *
 * // Create a retry policy with exponential backoff
 * val exponentialBackoffPolicy = OutboxRetryPolicy.builder()
 *     .exponentialBackoffDelay(
 *         initialDelay = Duration.ofSeconds(10),
 *         maxDelay = Duration.ofMinutes(5),
 *         backoffMultiplier = 2.0
 *     )
 *     .maxRetries(10)
 *     .build()
 *
 * // Create a retry policy with custom retry predicate
 * val customPolicy = OutboxRetryPolicy.builder()
 *     .retryPredicate { exception ->
 *         exception is RetryableException
 *     }
 *     .fixedDelay(Duration.ofSeconds(15), Duration.ofSeconds(5))
 *     .maxRetries(3)
 *     .build()
 *
 * // Create a retry policy with custom delay calculator
 * val customDelayPolicy = OutboxRetryPolicy.builder()
 *     .delayCalculator(myCustomDelayCalculator)
 *     .maxRetries(7)
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
        /**
         * Creates a builder for constructing an OutboxRetryPolicy with fluent API.
         *
         * The builder provides a convenient way to configure retry policies with various delay strategies,
         * custom retry predicates, and maximum retry attempts.
         *
         * @return A new Builder instance with default values (retry all exceptions, fixed delay, max 3 retries)
         * @see Builder
         */
        fun builder(): Builder = Builder()
    }

    /**
     * Builder for constructing OutboxRetryPolicy instances with fluent API.
     *
     * The Builder allows configuring three key aspects of retry behavior:
     * - **Retry Predicate**: Determines which exceptions should trigger retries
     * - **Delay Calculator**: Defines the delay strategy between retry attempts
     * - **Max Retries**: Sets the maximum number of retry attempts
     *
     * ## Default Values
     *
     * - Retry Predicate: Retries all exceptions
     * - Delay Calculator: Fixed delay with default configuration
     * - Max Retries: 3 attempts
     *
     * ## Usage Examples
     *
     * ```kotlin
     * // Simple fixed delay retry policy
     * val policy = OutboxRetryPolicy.builder()
     *     .fixedDelay(Duration.ofSeconds(30))
     *     .maxRetries(5)
     *     .build()
     *
     * // Exponential backoff with jitter
     * val policy = OutboxRetryPolicy.builder()
     *     .exponentialBackoffDelay(
     *         initialDelay = Duration.ofSeconds(10),
     *         maxDelay = Duration.ofMinutes(5),
     *         backoffMultiplier = 2.0,
     *         jitter = Duration.ofSeconds(5)
     *     )
     *     .maxRetries(10)
     *     .build()
     *
     * // Retry only specific exceptions
     * val policy = OutboxRetryPolicy.builder()
     *     .retryPredicate { exception ->
     *         exception is TransientException || exception is NetworkException
     *     }
     *     .fixedDelay(Duration.ofSeconds(15))
     *     .maxRetries(3)
     *     .build()
     *
     * // Custom delay calculator
     * val policy = OutboxRetryPolicy.builder()
     *     .delayCalculator(myCustomCalculator)
     *     .maxRetries(7)
     *     .build()
     * ```
     *
     * @property retryPredicate Function that determines if an exception should trigger a retry
     * @property delayCalculator Calculator that determines the delay between retry attempts
     * @property maxRetries Maximum number of retry attempts before marking as FAILED
     * @author Roland Beisel
     * @since 0.1.0
     */
    class Builder private constructor(
        private val retryPredicate: (Throwable) -> Boolean,
        private val delayCalculator: OutboxDelayCalculator,
        private val maxRetries: Int,
    ) {
        /**
         * Creates a new Builder instance with default values.
         *
         * Default configuration:
         * - Retry all exceptions
         * - Use fixed delay calculator with default settings
         * - Maximum 3 retry attempts
         */
        constructor() : this(
            retryPredicate = { true },
            delayCalculator = FixedDelayCalculator.builder().build(),
            maxRetries = 3,
        )

        /**
         * Sets a custom predicate to determine which exceptions should trigger retries.
         *
         * By default, all exceptions will trigger retries. Use this method to implement
         * selective retry logic based on exception type or properties.
         *
         * @param predicate Function that takes a Throwable and returns true if retry should be attempted
         * @return A new Builder instance with the specified retry predicate
         *
         * @sample
         * ```kotlin
         * builder.retryPredicate { exception ->
         *     exception is RetryableException || exception is TransientNetworkException
         * }
         * ```
         */
        fun retryPredicate(predicate: (Throwable) -> Boolean): Builder =
            Builder(
                retryPredicate = predicate,
                delayCalculator = delayCalculator,
                maxRetries = maxRetries,
            )

        /**
         * Sets a custom delay calculator to determine the delay between retry attempts.
         *
         * Use this method when you need full control over the delay calculation logic.
         * For common delay strategies, consider using [fixedDelay] or [exponentialBackoffDelay].
         *
         * @param delayCalculator The calculator that computes delay durations based on failure count
         * @return A new Builder instance with the specified delay calculator
         *
         * @see fixedDelay
         * @see exponentialBackoffDelay
         * @see OutboxDelayCalculator
         */
        fun delayCalculator(delayCalculator: OutboxDelayCalculator): Builder =
            Builder(
                retryPredicate = retryPredicate,
                delayCalculator = delayCalculator,
                maxRetries = maxRetries,
            )

        /**
         * Configures a fixed delay strategy without jitter.
         *
         * With this strategy, all retry attempts will wait the same duration before retrying.
         * This is the simplest retry strategy and works well for predictable scenarios.
         *
         * @param delay The fixed duration to wait between retry attempts
         * @return A new Builder instance with fixed delay configuration
         *
         * @see fixedDelay(Duration, Duration) for fixed delay with jitter
         * @see exponentialBackoffDelay for more sophisticated backoff strategies
         *
         * @sample
         * ```kotlin
         * builder.fixedDelay(Duration.ofSeconds(30))
         * ```
         */
        fun fixedDelay(delay: Duration): Builder =
            delayCalculator(
                delayCalculator =
                    FixedDelayCalculator
                        .builder()
                        .delay(delay)
                        .build(),
            )

        /**
         * Configures a fixed delay strategy with random jitter.
         *
         * This strategy waits a fixed duration plus/minus a random jitter value between retry attempts.
         * Jitter helps prevent thundering herd problems when multiple processes retry simultaneously.
         *
         * @param delay The base fixed duration to wait between retry attempts
         * @param jitter The maximum random variation to add or subtract from the delay
         * @return A new Builder instance with fixed delay and jitter configuration
         *
         * @see fixedDelay(Duration) for fixed delay without jitter
         * @see exponentialBackoffDelay for exponential backoff strategies
         *
         * @sample
         * ```kotlin
         * // Wait 30 seconds ± 5 seconds between retries
         * builder.fixedDelay(Duration.ofSeconds(30), Duration.ofSeconds(5))
         * ```
         */
        fun fixedDelay(
            delay: Duration,
            jitter: Duration,
        ): Builder =
            delayCalculator(
                delayCalculator =
                    FixedDelayCalculator
                        .builder()
                        .delay(delay)
                        .jitter(jitter)
                        .build(),
            )

        /**
         * Configures an exponential backoff delay strategy without jitter.
         *
         * With exponential backoff, the delay between retry attempts increases exponentially
         * with each failure, up to a maximum delay. This is effective for handling transient
         * failures that may take time to resolve.
         *
         * The delay for attempt n is calculated as:
         * `min(initialDelay * backoffMultiplier^(n-1), maxDelay)`
         *
         * @param initialDelay The delay before the first retry attempt
         * @param maxDelay The maximum delay between retry attempts (cap for exponential growth)
         * @param backoffMultiplier The multiplier for exponential growth (typically 2.0)
         * @return A new Builder instance with exponential backoff configuration
         *
         * @see exponentialBackoffDelay(Duration, Duration, Double, Duration) for exponential backoff with jitter
         * @see fixedDelay for simpler fixed delay strategies
         *
         * @sample
         * ```kotlin
         * // Delays: 10s, 20s, 40s, 80s, 160s, 300s (capped), 300s, ...
         * builder.exponentialBackoffDelay(
         *     initialDelay = Duration.ofSeconds(10),
         *     maxDelay = Duration.ofMinutes(5),
         *     backoffMultiplier = 2.0
         * )
         * ```
         */
        fun exponentialBackoffDelay(
            initialDelay: Duration,
            maxDelay: Duration,
            backoffMultiplier: Double,
        ): Builder =
            delayCalculator(
                delayCalculator =
                    ExponentialBackoffDelayCalculator
                        .builder()
                        .initialDelay(initialDelay)
                        .maxDelay(maxDelay)
                        .backoffMultiplier(backoffMultiplier)
                        .build(),
            )

        /**
         * Configures an exponential backoff delay strategy with random jitter.
         *
         * With exponential backoff and jitter, the delay increases exponentially with each failure,
         * and a random variation is added to prevent synchronized retry storms when multiple
         * processes fail simultaneously.
         *
         * The base delay for attempt n is calculated as:
         * `min(initialDelay * backoffMultiplier^(n-1), maxDelay)`
         * Then a random jitter value between -jitter and +jitter is added.
         *
         * @param initialDelay The delay before the first retry attempt
         * @param maxDelay The maximum delay between retry attempts (cap for exponential growth)
         * @param backoffMultiplier The multiplier for exponential growth (typically 2.0)
         * @param jitter The maximum random variation to add or subtract from the calculated delay
         * @return A new Builder instance with exponential backoff and jitter configuration
         *
         * @see exponentialBackoffDelay(Duration, Duration, Double) for exponential backoff without jitter
         * @see fixedDelay for simpler fixed delay strategies
         *
         * @sample
         * ```kotlin
         * // Delays: 10s±2s, 20s±2s, 40s±2s, 80s±2s, 160s±2s, 300s±2s (capped), ...
         * builder.exponentialBackoffDelay(
         *     initialDelay = Duration.ofSeconds(10),
         *     maxDelay = Duration.ofMinutes(5),
         *     backoffMultiplier = 2.0,
         *     jitter = Duration.ofSeconds(2)
         * )
         * ```
         */
        fun exponentialBackoffDelay(
            initialDelay: Duration,
            maxDelay: Duration,
            backoffMultiplier: Double,
            jitter: Duration,
        ): Builder =
            delayCalculator(
                delayCalculator =
                    ExponentialBackoffDelayCalculator
                        .builder()
                        .initialDelay(initialDelay)
                        .maxDelay(maxDelay)
                        .backoffMultiplier(backoffMultiplier)
                        .jitter(jitter)
                        .build(),
            )

        /**
         * Sets the maximum number of retry attempts before marking a record as FAILED.
         *
         * After this many failures, no further retry attempts will be made and the record
         * will be permanently marked as FAILED, requiring manual intervention.
         *
         * @param maxRetries The maximum number of retry attempts (must be positive)
         * @return A new Builder instance with the specified maximum retry count
         *
         * @sample
         * ```kotlin
         * builder.maxRetries(5) // Allow up to 5 retry attempts
         * ```
         */
        fun maxRetries(maxRetries: Int): Builder =
            Builder(
                retryPredicate = retryPredicate,
                delayCalculator = delayCalculator,
                maxRetries = maxRetries,
            )

        /**
         * Builds and returns an OutboxRetryPolicy instance with the configured settings.
         *
         * Creates an immutable retry policy using the configured retry predicate,
         * delay calculator, and maximum retry count.
         *
         * @return A new OutboxRetryPolicy instance with the specified configuration
         */
        fun build(): OutboxRetryPolicy =
            object : OutboxRetryPolicy {
                override fun shouldRetry(exception: Throwable): Boolean = retryPredicate(exception)

                override fun nextDelay(failureCount: Int): Duration = delayCalculator.calculate(failureCount)

                override fun maxRetries(): Int = maxRetries
            }
    }
}
