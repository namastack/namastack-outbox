package io.namastack.springoutbox.retry

import java.time.Duration

interface OutboxRetryPolicy {
    fun shouldRetry(exception: Throwable): Boolean

    fun nextDelay(retryCount: Int): Duration
}
