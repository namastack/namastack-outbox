package io.namastack.outbox.observability.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.OutboxChannelNameProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.scheduling.TaskScheduler
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ScheduledFuture

class OutboxMetricsCollectorLifecycleTest {
    private val task = mockk<ScheduledFuture<*>>(relaxed = true)
    private val scheduler =
        mockk<TaskScheduler> {
            every { scheduleWithFixedDelay(any<Runnable>(), any<Duration>()) } returns task
        }
    private val collector =
        OutboxMetricsCollector(
            recordRepository = mockk(),
            recordStatusRepository = mockk(),
            partitionCoordinator = mockk(),
            instanceRegistry = mockk(),
            properties = OutboxMetricsProperties(),
            clock = Clock.systemUTC(),
            taskScheduler = scheduler,
        )
    private val binder = OutboxInstanceMetricsMeterBinder(collector, OutboxChannelNameProvider.DEFAULT)
    private val registry = SimpleMeterRegistry()

    @AfterEach
    fun tearDown() {
        collector.stop()
        registry.close()
    }

    @Test
    fun `binding before lifecycle startup waits for startup`() {
        binder.bindTo(registry)
        assertThat(collector.isRunning).isFalse()
        verify { scheduler wasNot Called }

        collector.start()

        assertThat(collector.isRunning).isTrue()
        verify(exactly = 1) { scheduler.scheduleWithFixedDelay(any<Runnable>(), Duration.ofSeconds(15)) }
    }

    @Test
    fun `lifecycle startup before binding waits for a registry`() {
        collector.start()
        verify { scheduler wasNot Called }

        binder.bindTo(registry)
        collector.start()
        binder.bindTo(registry)
        val other = SimpleMeterRegistry()
        try {
            binder.bindTo(other)
        } finally {
            other.close()
        }

        verify(exactly = 1) { scheduler.scheduleWithFixedDelay(any<Runnable>(), Duration.ofSeconds(15)) }
    }

    @Test
    fun `binding after stop waits for an explicit lifecycle restart`() {
        collector.start()
        collector.stop()
        binder.bindTo(registry)
        assertThat(collector.isRunning).isFalse()
        verify { scheduler wasNot Called }

        collector.start()

        verify(exactly = 1) { scheduler.scheduleWithFixedDelay(any<Runnable>(), any<Duration>()) }
    }

    @Test
    fun `stop cancels scheduled collection and repeated binding does not restart it`() {
        binder.bindTo(registry)
        collector.start()
        collector.stop()
        collector.stop()
        binder.bindTo(registry)

        assertThat(collector.isRunning).isFalse()
        verify(exactly = 1) { task.cancel(true) }
        verify(exactly = 1) { scheduler.scheduleWithFixedDelay(any<Runnable>(), any<Duration>()) }

        collector.start()

        verify(exactly = 2) { scheduler.scheduleWithFixedDelay(any<Runnable>(), any<Duration>()) }
    }
}
