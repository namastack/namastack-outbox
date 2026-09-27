package io.namastack.outbox.runtime

import io.micrometer.observation.ObservationRegistry
import io.namastack.outbox.OutboxProcessingScheduler
import io.namastack.outbox.partition.PartitionCoordinator
import org.springframework.context.Lifecycle
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.support.ScheduledMethodRunnable
import java.lang.reflect.Method
import java.time.Duration
import java.util.concurrent.ScheduledFuture

/**
 * Schedules initial and recurring partition rebalancing for one programmatic outbox runtime.
 *
 * @param partitionCoordinator Coordinator invoked for each rebalance
 * @param taskScheduler Scheduler shared with runtime polling
 * @param interval Delay between completed rebalances
 * @param observationRegistry Registry used for scheduled-task observations
 *
 * @author Roland Beisel
 * @since 1.10.0
 */
internal class PartitionRebalanceScheduler(
    private val partitionCoordinator: PartitionCoordinator,
    private val taskScheduler: TaskScheduler,
    private val interval: Duration,
    private val observationRegistry: ObservationRegistry,
) : Lifecycle {
    companion object {
        private val REBALANCE_METHOD: Method = PartitionCoordinator::class.java.getMethod("rebalance")
    }

    @Volatile
    private var running = false

    private var scheduledTask: ScheduledFuture<*>? = null

    override fun isRunning(): Boolean = running

    @Synchronized
    override fun start() {
        if (running) return

        partitionCoordinator.rebalance()
        val runnable =
            ScheduledMethodRunnable(
                partitionCoordinator,
                REBALANCE_METHOD,
                OutboxProcessingScheduler.SCHEDULER_NAME,
                { observationRegistry },
            )

        running = true
        try {
            val firstExecution = taskScheduler.clock.instant().plus(interval)
            scheduledTask =
                checkNotNull(taskScheduler.scheduleWithFixedDelay(runnable, firstExecution, interval)) {
                    "TaskScheduler did not schedule partition rebalancing"
                }
        } catch (failure: Throwable) {
            running = false
            throw failure
        }
    }

    @Synchronized
    override fun stop() {
        if (!running) return

        scheduledTask?.cancel(false)
        scheduledTask = null
        running = false
    }
}
