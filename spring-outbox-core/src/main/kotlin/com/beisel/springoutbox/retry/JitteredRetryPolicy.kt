package com.beisel.springoutbox.retry

import java.time.Duration

class JitteredRetryPolicy(
    private val basePolicy: OutboxRetryPolicy,
    private val jitter: Duration,
) : OutboxRetryPolicy {
    override fun shouldRetry(exception: Throwable): Boolean = basePolicy.shouldRetry(exception)

    override fun nextDelay(retryCount: Int): Duration {
        val baseDelay = basePolicy.nextDelay(retryCount)
        val jitterMillis = (Math.random() * jitter.toMillis()).toLong()

        return baseDelay.plusMillis(jitterMillis)
    }
}
