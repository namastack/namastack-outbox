package io.namastack.outbox.runtime

import io.micrometer.observation.ObservationRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.namastack.outbox.partition.PartitionCoordinator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.scheduling.TaskScheduler
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ScheduledFuture

class PartitionRebalanceSchedulerTest {
    @Test
    fun `rebalances immediately and schedules observed periodic rebalancing`() {
        val coordinator = mockk<PartitionCoordinator>(relaxed = true)
        val taskScheduler = mockk<TaskScheduler>()
        val scheduledFuture = mockk<ScheduledFuture<*>>(relaxed = true)
        val runnable = slot<Runnable>()
        every { taskScheduler.clock } returns CLOCK
        every {
            taskScheduler.scheduleWithFixedDelay(capture(runnable), CLOCK.instant().plus(INTERVAL), INTERVAL)
        } returns scheduledFuture
        val scheduler =
            PartitionRebalanceScheduler(
                partitionCoordinator = coordinator,
                taskScheduler = taskScheduler,
                interval = INTERVAL,
                observationRegistry = ObservationRegistry.NOOP,
            )

        scheduler.start()
        scheduler.start()
        runnable.captured.run()

        assertThat(scheduler.isRunning).isTrue()
        verify(exactly = 2) { coordinator.rebalance() }
        verify(exactly = 1) {
            taskScheduler.scheduleWithFixedDelay(any(), CLOCK.instant().plus(INTERVAL), INTERVAL)
        }

        scheduler.stop()
        scheduler.stop()

        assertThat(scheduler.isRunning).isFalse()
        verify(exactly = 1) { scheduledFuture.cancel(false) }
    }

    private companion object {
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
        val INTERVAL: Duration = Duration.ofSeconds(10)
    }
}
