package io.namastack.outbox.trigger

import org.springframework.scheduling.TriggerContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Adaptive polling trigger that dynamically adjusts the polling interval based on workload.
 *
 * This trigger automatically increases the polling delay when few records are processed
 * (system is idle) and decreases it when many records are found (system is busy). This
 * approach helps reduce unnecessary database queries during idle periods while maintaining
 * responsiveness during high load.
 *
 * The trigger uses the following strategy:
 * - If fewer than 25% of batch size records are processed, the delay is doubled (up to maxDelay)
 * - If a full batch is processed, the delay is halved (down to minDelay)
 * - Otherwise, the delay remains unchanged
 *
 * @param minDelay The minimum duration to wait between polling cycles
 * @param maxDelay The maximum duration to wait between polling cycles
 * @param batchSize The batch size used to determine workload thresholds
 * @param clock The clock used for time calculations, useful for testing
 *
 * @author Aleksander Zamojski
 * @since 1.1.0
 * @see OutboxPollingTrigger
 */
class AdaptivePollingTrigger(
    private val minDelay: Duration,
    private val maxDelay: Duration,
    private val batchSize: Int,
    private val clock: Clock,
) : OutboxPollingTrigger {
    /**
     * Multiplier used for exponential backoff when increasing or decreasing delay.
     */
    private val backoffMultiplier: Long = 2

    /**
     * Current delay in milliseconds, adjusted dynamically based on workload.
     */
    private val currentDelayMs = AtomicLong(minDelay.toMillis())

    /**
     * Calculates the next execution time using the current adaptive delay.
     *
     * The delay is determined by the [currentDelayMs] value, which is adjusted
     * by [onTaskComplete] based on the number of records processed.
     *
     * @param context The trigger context containing execution history
     * @return The instant when the next execution should occur
     */
    override fun nextExecution(context: TriggerContext): Instant {
        val lastCompletion = context.lastCompletion() ?: Instant.now(clock)
        val delay = currentDelayMs.get()
        return lastCompletion.plusMillis(delay)
    }

    /**
     * Adjusts the polling delay based on the number of records processed.
     *
     * Implements the adaptive logic:
     * - If recordCount ≤ batchSize/4: Doubles the delay (up to maxDelay) - system is idle
     * - If recordCount == batchSize: Halves the delay (down to minDelay) - system is busy
     * - Otherwise: No change - moderate load
     *
     * Thread-safe: Uses atomic operations to update the delay.
     *
     * @param recordCount The number of records processed in the completed task
     */
    override fun onTaskComplete(recordCount: Int) {
        if (recordCount <= batchSize / 4) {
            currentDelayMs.updateAndGet { current ->
                (current * backoffMultiplier).coerceAtMost(maxDelay.toMillis())
            }
        } else if (recordCount == batchSize) {
            currentDelayMs.updateAndGet { current ->
                (current / backoffMultiplier).coerceAtLeast(minDelay.toMillis())
            }
        }
    }
}
