package io.namastack.outbox.retry

import java.time.Duration

/**
 * A backoff strategy that returns a fixed delay for all retry attempts.
 *
 * @param delay The fixed delay duration between retries
 * @author Aleksander Zamojski
 * @since 1.0.0
 */
class FixedBackOffStrategy(
    private val delay: Duration,
) : BackOffStrategy {
    override fun nextDelay(failureCount: Int): Duration = delay
}
