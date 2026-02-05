package io.namastack.outbox.trigger

import org.springframework.scheduling.TriggerContext
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Fixed interval polling trigger that schedules the next execution at a constant delay
 * after the previous task completion.
 *
 * This trigger maintains a consistent polling interval regardless of the number of records
 * processed. It is suitable for scenarios with predictable workloads or when you want
 * to maintain a steady polling rate.
 *
 * @param delay The fixed duration to wait between polling cycles
 * @param clock The clock used for time calculations, useful for testing
 *
 * @author Aleksander Zamojski
 * @since 1.1.0
 * @see OutboxPollingTrigger
 */
class FixedPollingTrigger(
    private val delay: Duration,
    private val clock: Clock,
) : OutboxPollingTrigger {
    /**
     * Calculates the next execution time by adding the fixed delay to the last completion time.
     *
     * @param context The trigger context containing execution history
     * @return The instant when the next execution should occur
     */
    override fun nextExecution(context: TriggerContext): Instant {
        val lastCompletion = context.lastCompletion() ?: Instant.now(clock)
        return lastCompletion.plus(delay)
    }
}
