package com.beisel.springoutbox.retry

import java.time.Duration
import kotlin.math.pow

class ExponentialBackoffRetryPolicy(
    private val initialDelay: Duration,
    private val maxDelay: Duration,
) : OutboxRetryPolicy {
    override fun shouldRetry(exception: Throwable): Boolean = true

    override fun nextDelay(retryCount: Int): Duration {
        val delayMillis = (initialDelay.toMillis() * 2.0.pow(retryCount.toDouble())).toLong()

        return Duration.ofMillis(minOf(delayMillis, maxDelay.toMillis()))
    }
}
