package io.namastack.outbox.retry

import java.time.Duration

/**
 * Interface for retry policies that determine when and how to retry failed outbox record processing.
 *
 * Implementations define the retry behavior including whether to retry based on the exception
 * and what delay to use between retry attempts.
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
         * Creates a builder for constructing a OutboxRetryPolicy with fluent API.
         *
         * @return A new Builder instance
         */
        fun builder(): Builder = Builder()
    }

    class Builder private constructor(
        private val delayCalculator: OutboxDelayCalculator,
        private val maxRetries: Int,
        private val predicate: (Throwable) -> Boolean,
    ) {
        constructor() : this(
            delayCalculator = FixedDelayCalculator.builder().build(),
            maxRetries = 3,
            predicate = { true },
        )

        fun fixedDelay(delay: Duration): Builder =
            Builder(
                delayCalculator =
                    FixedDelayCalculator
                        .builder()
                        .delay(delay)
                        .build(),
                maxRetries = maxRetries,
                predicate = predicate,
            )

        fun fixedDelay(
            delay: Duration,
            jitter: Duration,
        ): Builder =
            Builder(
                delayCalculator =
                    FixedDelayCalculator
                        .builder()
                        .delay(delay)
                        .jitter(jitter)
                        .build(),
                maxRetries = maxRetries,
                predicate = predicate,
            )

        fun exponentialBackoffDelay(
            initialDelay: Duration,
            maxDelay: Duration,
            backoffMultiplier: Double,
        ): Builder =
            Builder(
                delayCalculator =
                    ExponentialBackoffDelayCalculator
                        .builder()
                        .initialDelay(initialDelay)
                        .maxDelay(maxDelay)
                        .backoffMultiplier(backoffMultiplier)
                        .build(),
                maxRetries = maxRetries,
                predicate = predicate,
            )

        fun exponentialBackoffDelay(
            initialDelay: Duration,
            maxDelay: Duration,
            backoffMultiplier: Double,
            jitter: Duration,
        ): Builder =
            Builder(
                delayCalculator =
                    ExponentialBackoffDelayCalculator
                        .builder()
                        .initialDelay(initialDelay)
                        .maxDelay(maxDelay)
                        .backoffMultiplier(backoffMultiplier)
                        .jitter(jitter)
                        .build(),
                maxRetries = maxRetries,
                predicate = predicate,
            )

        fun maxRetries(maxRetries: Int): Builder =
            Builder(
                delayCalculator = delayCalculator,
                maxRetries = maxRetries,
                predicate = predicate,
            )

        fun shouldRetry(predicate: (Throwable) -> Boolean): Builder =
            Builder(
                delayCalculator = delayCalculator,
                maxRetries = maxRetries,
                predicate = predicate,
            )

        fun build(): OutboxRetryPolicy =
            object : OutboxRetryPolicy {
                override fun shouldRetry(exception: Throwable): Boolean = predicate(exception)

                override fun nextDelay(failureCount: Int): Duration = delayCalculator.calculate(failureCount)

                override fun maxRetries(): Int = maxRetries
            }
    }
}
