package io.namastack.outbox.retry

import java.time.Duration
import kotlin.math.pow

/**
 * A backoff strategy that increases the delay exponentially with each retry attempt.
 *
 * The delay is calculated as: `initialDelay * multiplier^(failureCount - 1)`,
 * capped at `maxDelay`.
 *
 * Example with initialDelay=1s, multiplier=2.0, maxDelay=60s:
 * - Retry 1: 1s
 * - Retry 2: 2s
 * - Retry 3: 4s
 * - Retry 4: 8s
 * - Retry 5: 16s
 * - Retry 6: 32s
 * - Retry 7+: 60s (capped)
 *
 * @param initialDelay The initial delay duration for the first retry
 * @param multiplier The multiplication factor for exponential growth (typically 2.0)
 * @param maxDelay The maximum delay duration (cap)
 * @author Aleksander Zamojski
 * @since 1.0.0
 */
class ExponentialBackOffStrategy(
    private val initialDelay: Duration,
    private val multiplier: Double,
    private val maxDelay: Duration,
) : BackOffStrategy {
    override fun nextDelay(failureCount: Int): Duration {
        val delayMillis = (initialDelay.toMillis() * multiplier.pow(failureCount - 1)).toLong()
        return Duration.ofMillis(minOf(delayMillis, maxDelay.toMillis()))
    }
}
