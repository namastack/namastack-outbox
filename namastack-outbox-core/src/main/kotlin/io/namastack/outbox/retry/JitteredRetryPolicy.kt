package io.namastack.outbox.retry

import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Retry policy that adds random jitter to a base retry policy.
 *
 * This policy wraps another retry policy and adds random jitter to the delay
 * to help avoid thundering herd problems when multiple instances retry at
 * the same time.
 *
 * @param basePolicy The underlying retry policy to add jitter to
 * @param jitter Maximum jitter duration to add
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
class JitteredRetryPolicy(
    private val basePolicy: OutboxRetryPolicy,
    private val jitter: Duration,
) : OutboxRetryPolicy {
    private val log = LoggerFactory.getLogger(JitteredRetryPolicy::class.java)

    /**
     * Delegates the retry decision to the base policy.
     *
     * @param exception The exception that occurred
     * @return True if the base policy says to retry
     */
    override fun shouldRetry(exception: Throwable): Boolean = basePolicy.shouldRetry(exception)

    /**
     * Calculates the next delay by adding random jitter to the base policy's delay.
     *
     * @param failureCount The number of failures that have occurred
     * @return Base delay plus random jitter
     */
    override fun nextDelay(failureCount: Int): Duration {
        val baseDelay = basePolicy.nextDelay(failureCount)
        val jitterMillis = (Math.random() * jitter.toMillis()).toLong()
        val finalDelay = baseDelay.plusMillis(jitterMillis)

        log.debug(
            "Jittered retry delay calculation: retry #{} -> base delay: {}ms (from {}), jitter: +{}ms (max: {}ms), final delay: {}ms",
            failureCount,
            baseDelay.toMillis(),
            basePolicy.javaClass.simpleName,
            jitterMillis,
            jitter.toMillis(),
            finalDelay.toMillis(),
        )

        return finalDelay
    }
}
