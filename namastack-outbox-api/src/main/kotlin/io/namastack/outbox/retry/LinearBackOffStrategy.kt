package io.namastack.outbox.retry

import java.time.Duration

/**
 * A backoff strategy that increases the delay linearly with each retry attempt.
 *
 * The delay is calculated as: `initialDelay + increment * (failureCount - 1)`,
 * capped at `maxDelay`.
 *
 * Example with initialDelay=5s, increment=5s, maxDelay=30s:
 * - Retry 1: 5s
 * - Retry 2: 10s
 * - Retry 3: 15s
 * - Retry 4: 20s
 * - Retry 5: 25s
 * - Retry 6+: 30s (capped)
 *
 * @param initialDelay The initial delay duration for the first retry
 * @param increment The amount to increase the delay by for each subsequent retry
 * @param maxDelay The maximum delay duration (cap)
 * @author Aleksander Zamojski
 * @since 1.0.0
 */
class LinearBackOffStrategy(
    private val initialDelay: Duration,
    private val increment: Duration,
    private val maxDelay: Duration,
) : BackOffStrategy {
    override fun nextDelay(failureCount: Int): Duration {
        val delayMillis = initialDelay.toMillis() + increment.toMillis() * (failureCount - 1)
        return Duration.ofMillis(minOf(delayMillis, maxDelay.toMillis()))
    }
}
