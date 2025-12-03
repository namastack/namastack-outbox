package io.namastack.outbox.retry

import java.time.Duration

/**
 * Retry policy that uses a fixed delay between retry attempts.
 *
 * This policy always waits the same amount of time between retries,
 * regardless of the retry count or exception type.
 *
 * @param delay Fixed delay duration between retries
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
class FixedDelayRetryPolicy(
    private val delay: Duration,
) : OutboxRetryPolicy {
    /**
     * Always returns true, indicating that retries should be attempted.
     *
     * @param exception The exception that occurred
     * @return Always true
     */
    override fun shouldRetry(exception: Throwable): Boolean = true

    /**
     * Returns the fixed delay duration for all retry attempts.
     *
     * @param failureCount The number of failures that have occurred (ignored)
     * @return The fixed delay duration
     */
    override fun nextDelay(failureCount: Int): Duration = delay
}
