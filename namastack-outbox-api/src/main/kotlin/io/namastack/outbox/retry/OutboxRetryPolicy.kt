package io.namastack.outbox.retry

import java.time.Duration
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
        fun builder(): Builder = Builder()
    }

    class Builder private constructor(
        private var retryableExceptions: Collection<Class<out Throwable>>,
        private var nonRetryableExceptions: Collection<Class<out Throwable>>,
        private val retryPredicate: ((Throwable) -> Boolean)?,
        private val backOffStrategy: BackOffStrategy,
        private val jitter: Duration?,
        private val maxRetries: Int,
    ) {
        constructor() : this(
            retryPredicate = null,
            retryableExceptions = emptyList(),
            nonRetryableExceptions = emptyList(),
            backOffStrategy = FixedBackOffStrategy(Duration.ofSeconds(5)),
            jitter = null,
            maxRetries = 3,
        )

        fun retryOn(vararg exceptions: Class<out Throwable>): Builder =
            retryOn(
                exceptions = exceptions.toList(),
            )

        fun retryOn(exceptions: Collection<Class<out Throwable>>): Builder =
            Builder(
                retryPredicate = retryPredicate,
                retryableExceptions = exceptions,
                nonRetryableExceptions = nonRetryableExceptions,
                backOffStrategy = backOffStrategy,
                jitter = jitter,
                maxRetries = maxRetries,
            )

        fun noRetryOn(vararg exceptions: Class<out Throwable>): Builder =
            noRetryOn(
                exceptions = exceptions.toList(),
            )

        fun noRetryOn(exceptions: Collection<Class<out Throwable>>): Builder =
            Builder(
                retryPredicate = retryPredicate,
                retryableExceptions = retryableExceptions,
                nonRetryableExceptions = exceptions,
                backOffStrategy = backOffStrategy,
                jitter = jitter,
                maxRetries = maxRetries,
            )

        fun retryIf(predicate: (Throwable) -> Boolean): Builder =
            Builder(
                retryPredicate = predicate,
                retryableExceptions = retryableExceptions,
                nonRetryableExceptions = nonRetryableExceptions,
                backOffStrategy = backOffStrategy,
                jitter = jitter,
                maxRetries = maxRetries,
            )

        fun backOff(strategy: BackOffStrategy): Builder =
            Builder(
                retryPredicate = retryPredicate,
                retryableExceptions = retryableExceptions,
                nonRetryableExceptions = nonRetryableExceptions,
                backOffStrategy = strategy,
                jitter = jitter,
                maxRetries = maxRetries,
            )

        fun fixedBackOff(delay: Duration): Builder =
            backOff(
                strategy = FixedBackOffStrategy(delay = delay),
            )

        fun linearBackoff(
            initialDelay: Duration,
            increment: Duration,
            maxDelay: Duration,
        ): Builder =
            backOff(
                strategy =
                    LinearBackOffStrategy(
                        initialDelay = initialDelay,
                        increment = increment,
                        maxDelay = maxDelay,
                    ),
            )

        fun exponentialBackoff(
            initialDelay: Duration,
            multiplier: Double,
            maxDelay: Duration,
        ): Builder =
            backOff(
                strategy =
                    ExponentialBackOffStrategy(
                        initialDelay = initialDelay,
                        multiplier = multiplier,
                        maxDelay = maxDelay,
                    ),
            )

        fun jitter(jitter: Duration): Builder =
            Builder(
                retryPredicate = retryPredicate,
                retryableExceptions = retryableExceptions,
                nonRetryableExceptions = nonRetryableExceptions,
                backOffStrategy = backOffStrategy,
                jitter = jitter,
                maxRetries = maxRetries,
            )

        fun maxRetries(maxRetries: Int): Builder =
            Builder(
                retryPredicate = retryPredicate,
                retryableExceptions = retryableExceptions,
                nonRetryableExceptions = nonRetryableExceptions,
                backOffStrategy = backOffStrategy,
                jitter = jitter,
                maxRetries = maxRetries,
            )

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
                    if (retryPredicate != null && retryPredicate(exception)) {
                        return true
                    }

                    return false
                }

                override fun nextDelay(failureCount: Int): Duration {
                    val baseDelay = backOffStrategy.nextDelay(failureCount)

                    return if (jitter != null) {
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
}

internal class FixedBackOffStrategy(
    private val delay: Duration,
) : BackOffStrategy {
    override fun nextDelay(failureCount: Int): Duration = delay
}

internal class LinearBackOffStrategy(
    private val initialDelay: Duration,
    private val increment: Duration,
    private val maxDelay: Duration,
) : BackOffStrategy {
    override fun nextDelay(failureCount: Int): Duration {
        val delayMillis = initialDelay.toMillis() + increment.toMillis() * (failureCount - 1)

        return Duration.ofMillis(minOf(delayMillis, maxDelay.toMillis()))
    }
}

internal class ExponentialBackOffStrategy(
    private val initialDelay: Duration,
    private val multiplier: Double,
    private val maxDelay: Duration,
) : BackOffStrategy {
    override fun nextDelay(failureCount: Int): Duration {
        val delayMillis = (initialDelay.toMillis() * multiplier.pow(failureCount - 1)).toLong()

        return Duration.ofMillis(minOf(delayMillis, maxDelay.toMillis()))
    }
}
