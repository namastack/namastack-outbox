package io.namastack.outbox.retry

import java.time.Duration

interface BackOffStrategy {
    fun nextDelay(failureCount: Int): Duration
}
