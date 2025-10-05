package io.namastack.springoutbox.retry

import org.slf4j.LoggerFactory
import java.time.Duration

class JitteredRetryPolicy(
    private val basePolicy: OutboxRetryPolicy,
    private val jitter: Duration,
) : OutboxRetryPolicy {
    private val log = LoggerFactory.getLogger(JitteredRetryPolicy::class.java)

    override fun shouldRetry(exception: Throwable): Boolean = basePolicy.shouldRetry(exception)

    override fun nextDelay(retryCount: Int): Duration {
        val baseDelay = basePolicy.nextDelay(retryCount)
        val jitterMillis = (Math.random() * jitter.toMillis()).toLong()
        val finalDelay = baseDelay.plusMillis(jitterMillis)

        log.debug(
            "🎲 Jittered retry delay calculation: retry #{} -> base delay: {}ms (from {}), jitter: +{}ms (max: {}ms), final delay: {}ms",
            retryCount,
            baseDelay.toMillis(),
            basePolicy.javaClass.simpleName,
            jitterMillis,
            jitter.toMillis(),
            finalDelay.toMillis(),
        )

        return finalDelay
    }
}
