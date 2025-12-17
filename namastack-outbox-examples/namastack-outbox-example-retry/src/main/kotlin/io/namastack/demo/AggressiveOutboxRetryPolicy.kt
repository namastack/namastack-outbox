package io.namastack.demo

import io.namastack.outbox.retry.OutboxRetryPolicy
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class AggressiveOutboxRetryPolicy : OutboxRetryPolicy {
    override fun maxRetries(): Int = 5

    override fun nextDelay(failureCount: Int): Duration = Duration.ofSeconds(0)

    override fun shouldRetry(exception: Throwable): Boolean = true
}
