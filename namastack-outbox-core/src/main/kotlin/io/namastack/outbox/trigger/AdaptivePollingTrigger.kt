package io.namastack.outbox.trigger

import org.springframework.scheduling.TriggerContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Adaptive polling trigger that dynamically adjusts the polling interval based on workload.
 *
 * This trigger increases the polling delay when key queries find little work (system is idle)
 * and decreases it when they are full (system is busy). This reduces unnecessary database
 * queries during idle periods while maintaining responsiveness during high load.
 *
 * The trigger applies the following rule to every poll result reported through [onPollResult]:
 * - If at most 25% of the requested keys are found, the delay is doubled (up to maxDelay)
 * - If all requested keys are found, the delay is halved (down to minDelay)
 * - Otherwise, the delay remains unchanged
 *
 * @param minDelay The minimum duration to wait between polling cycles
 * @param maxDelay The maximum duration to wait between polling cycles
 * @param clock The clock used for time calculations, useful for testing
 * @throws IllegalArgumentException if [minDelay] is less than 1 millisecond or [maxDelay] is less than [minDelay]
 *
 * @author Aleksander Zamojski
 * @since 1.1.0
 * @see OutboxPollingTrigger
 */
class AdaptivePollingTrigger(
    private val minDelay: Duration,
    private val maxDelay: Duration,
    private val clock: Clock,
) : OutboxPollingTrigger {
    init {
        require(minDelay.toMillis() >= 1) { "Adaptive polling min-interval must be at least 1ms, but was $minDelay" }
        require(maxDelay >= minDelay) {
            "Adaptive polling max-interval must not be less than min-interval, but was $maxDelay < $minDelay"
        }
    }

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
     * by [onPollResult] based on the reported poll results.
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
     * Adjusts the polling delay based on a poll result.
     *
     * Implements the adaptive logic:
     * - If found ≤ requested/4: Doubles the delay (up to maxDelay) - system is idle
     * - If found ≥ requested: Halves the delay (down to minDelay) - system is busy
     * - Otherwise: No change - moderate load
     *
     * A result with no requested keys leaves the delay unchanged.
     *
     * Thread-safe: Uses atomic operations to update the delay.
     *
     * @param found Number of record keys selected for processing
     * @param requested Maximum number of record keys the query could select for processing
     */
    override fun onPollResult(
        found: Int,
        requested: Int,
    ) {
        if (requested <= 0) return

        if (found >= requested) {
            currentDelayMs.updateAndGet { current ->
                (current / backoffMultiplier).coerceAtLeast(minDelay.toMillis())
            }
        } else if (found <= requested / 4) {
            currentDelayMs.updateAndGet { current ->
                (current * backoffMultiplier).coerceAtMost(maxDelay.toMillis())
            }
        }
    }
}
