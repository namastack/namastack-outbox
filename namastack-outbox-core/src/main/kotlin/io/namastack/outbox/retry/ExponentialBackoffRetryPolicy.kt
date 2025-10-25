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
 * @param initialDelay Initial delay for the first retry
 * @param maxDelay Maximum delay between retries
 * @param backoffMultiplier Multiplier for exponential backoff calculation
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
class ExponentialBackoffRetryPolicy(
    private val initialDelay: Duration,
    private val maxDelay: Duration,
    private val backoffMultiplier: Double,
) : OutboxRetryPolicy {
    /**
     * Always returns true, indicating that retries should be attempted.
     *
     * @param exception The exception that occurred
     * @return Always true
     */
    override fun shouldRetry(exception: Throwable): Boolean = true

    /**
     * Calculates the next delay using exponential backoff.
     *
     * The delay is calculated as: initialDelay * (backoffMultiplier ^ retryCount)
     * The result is capped at the maximum delay.
     *
     * @param retryCount The current retry count
     * @return Calculated delay duration, capped at maxDelay
     */
    override fun nextDelay(retryCount: Int): Duration {
        val delayMillis = (initialDelay.toMillis() * backoffMultiplier.pow(retryCount.toDouble())).toLong()

        return Duration.ofMillis(minOf(delayMillis, maxDelay.toMillis()))
    }
}
