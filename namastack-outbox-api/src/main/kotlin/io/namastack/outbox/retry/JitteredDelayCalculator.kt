package io.namastack.outbox.retry

import java.time.Duration

/**
 * Retry policy that adds random jitter to a base retry policy.
 *
 * This policy wraps another retry policy and adds random jitter to the delay
 * to help avoid thundering herd problems when multiple instances retry at
 * the same time.
 *
 * @param basePolicy The underlying retry policy to add jitter to
 * @param jitter Maximum jitter duration to add
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
class JitteredDelayCalculator(
    private val basePolicy: OutboxDelayCalculator,
    private val jitter: Duration,
) : OutboxDelayCalculator {
    /**
     * Calculates the next delay by adding random jitter to the base policy's delay.
     *
     * @param failureCount The number of failures that have occurred
     * @return Base delay plus random jitter
     */
    override fun calculate(failureCount: Int): Duration {
        val baseDelay = basePolicy.calculate(failureCount)
        val jitterMillis = (Math.random() * jitter.toMillis()).toLong()
        return baseDelay.plusMillis(jitterMillis)
    }
}
