package io.namastack.outbox.retry

import java.time.Duration

class FixedDelayRetryPolicy(
    private val delay: Duration,
) : OutboxRetryPolicy {
    override fun shouldRetry(exception: Throwable): Boolean = true

    override fun nextDelay(retryCount: Int): Duration = delay
}
