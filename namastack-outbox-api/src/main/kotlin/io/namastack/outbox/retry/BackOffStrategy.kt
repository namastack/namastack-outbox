package io.namastack.outbox.retry

import java.time.Duration

/**
 * Strategy interface for calculating the delay between retry attempts.
 *
 * Implementations define different backoff algorithms such as fixed delay,
 * exponential backoff, or exponential backoff with jitter.
 *
 * @author Aleksander Zamojski
 * @since 1.0.0-RC2
 */
fun interface BackOffStrategy {
    /**
     * Calculates the delay before the next retry attempt.
     *
     * @param failureCount The number of failures that have occurred (starting from 1)
     * @return Duration to wait before the next retry attempt
     */
    fun nextDelay(failureCount: Int): Duration
}
