package io.namastack.outbox.retry

import java.time.Duration

interface OutboxDelayCalculator {
    fun calculate(failureCount: Int): Duration
}
