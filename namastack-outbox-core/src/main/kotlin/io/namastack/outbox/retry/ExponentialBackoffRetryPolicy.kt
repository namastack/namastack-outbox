package io.namastack.outbox.retry

import java.time.Duration
import kotlin.math.pow

class ExponentialBackoffRetryPolicy(
    private val initialDelay: Duration,
    private val maxDelay: Duration,
    private val backoffMultiplier: Double,
) : OutboxRetryPolicy {
    override fun shouldRetry(exception: Throwable): Boolean = true

    override fun nextDelay(retryCount: Int): Duration {
        val delayMillis = (initialDelay.toMillis() * backoffMultiplier.pow(retryCount.toDouble())).toLong()

        return Duration.ofMillis(minOf(delayMillis, maxDelay.toMillis()))
    }
}
