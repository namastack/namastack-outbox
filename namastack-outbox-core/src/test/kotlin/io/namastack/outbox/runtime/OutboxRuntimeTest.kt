package io.namastack.outbox.runtime

import io.micrometer.observation.ObservationRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.Outbox
import io.namastack.outbox.OutboxProcessingScheduler
import io.namastack.outbox.instance.OutboxInstanceRegistry
import io.namastack.outbox.partition.PartitionCoordinator
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.scheduling.TaskScheduler
import java.time.Duration
import java.util.concurrent.ScheduledFuture

class OutboxRuntimeTest {
    @Test
    fun `starts in dependency order and ignores repeated start`() {
        val events = mutableListOf<String>()
        val components = components(events)
        val runtime = runtime(components)

        runtime.start()
        runtime.start()

        assertThat(events)
            .containsExactly("instance.start", "partition.rebalance", "rebalance.schedule", "processing.start")
        assertThat(runtime.isRunning()).isTrue()
        verify(exactly = 1) { components.instanceRegistry.start() }
        verify(exactly = 1) { components.processingScheduler.start() }
    }

    @Test
    fun `closes in reverse order once and leaves borrowed resources open`() {
        val events = mutableListOf<String>()
        val components = components(events)
        val persistenceResource = RecordingCloseable("persistence.close", events)
        val threadingResource = RecordingCloseable("threading.close", events)
        val borrowedResource = components.taskScheduler as AutoCloseable
        val runtime =
            runtime(
                components = components,
                persistenceResources = listOf(persistenceResource),
                threadingResources = listOf(threadingResource),
            )
        runtime.start()
        events.clear()

        runtime.close()
        runtime.close()

        assertThat(events)
            .containsExactly(
                "processing.stop",
                "rebalance.cancel",
                "instance.stop",
                "persistence.close",
                "threading.close",
            )
        assertThat(runtime.isRunning()).isFalse()
        assertThat(persistenceResource.closeCount).isEqualTo(1)
        assertThat(threadingResource.closeCount).isEqualTo(1)
        verify(exactly = 0) { borrowedResource.close() }
    }

    @Test
    fun `rolls back partial startup and closes owned resources`() {
        val events = mutableListOf<String>()
        val components = components(events)
        val failure = IllegalStateException("processing start failed")
        val persistenceResource = RecordingCloseable("persistence.close", events)
        val threadingResource = RecordingCloseable("threading.close", events)
        every { components.processingScheduler.isRunning } returns false
        every { components.processingScheduler.start() } answers {
            events += "processing.start"
            throw failure
        }
        val runtime =
            runtime(
                components = components,
                persistenceResources = listOf(persistenceResource),
                threadingResources = listOf(threadingResource),
            )

        assertThatThrownBy(runtime::start).isSameAs(failure)

        assertThat(events)
            .containsExactly(
                "instance.start",
                "partition.rebalance",
                "rebalance.schedule",
                "processing.start",
                "rebalance.cancel",
                "instance.stop",
                "persistence.close",
                "threading.close",
            )
        assertThat(runtime.isRunning()).isFalse()
        verify(exactly = 1) { components.processingScheduler.start() }

        runtime.close()
        assertThat(persistenceResource.closeCount).isEqualTo(1)
        assertThat(threadingResource.closeCount).isEqualTo(1)
        assertThatThrownBy(runtime::start)
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Outbox runtime is already closed")
    }

    private fun components(events: MutableList<String>): RuntimeComponents {
        val instanceRegistry = mockk<OutboxInstanceRegistry>()
        val partitionCoordinator = mockk<PartitionCoordinator>()
        val processingScheduler = mockk<OutboxProcessingScheduler>()
        val taskScheduler = mockk<TaskScheduler>(moreInterfaces = arrayOf(AutoCloseable::class))
        val scheduledRebalance = mockk<ScheduledFuture<*>>()

        every { instanceRegistry.start() } answers { events += "instance.start" }
        every { instanceRegistry.isRunning } returns true
        every { instanceRegistry.stop() } answers { events += "instance.stop" }
        every { partitionCoordinator.rebalance() } answers { events += "partition.rebalance" }
        every { taskScheduler.scheduleWithFixedDelay(any<Runnable>(), REBALANCE_INTERVAL) } answers {
            events += "rebalance.schedule"
            scheduledRebalance
        }
        every { scheduledRebalance.cancel(false) } answers {
            events += "rebalance.cancel"
            true
        }
        every { processingScheduler.start() } answers { events += "processing.start" }
        every { processingScheduler.isRunning } returns true
        every { processingScheduler.stop() } answers { events += "processing.stop" }

        return RuntimeComponents(
            instanceRegistry = instanceRegistry,
            partitionCoordinator = partitionCoordinator,
            processingScheduler = processingScheduler,
            taskScheduler = taskScheduler,
        )
    }

    private fun runtime(
        components: RuntimeComponents,
        persistenceResources: List<AutoCloseable> = emptyList(),
        threadingResources: List<AutoCloseable> = emptyList(),
    ): OutboxRuntime =
        OutboxRuntime(
            outbox = mockk<Outbox>(),
            instanceRegistry = components.instanceRegistry,
            partitionCoordinator = components.partitionCoordinator,
            processingScheduler = components.processingScheduler,
            taskScheduler = components.taskScheduler,
            rebalanceInterval = REBALANCE_INTERVAL,
            observationRegistry = { ObservationRegistry.NOOP },
            ownedPersistenceResources = persistenceResources,
            ownedThreadingResources = threadingResources,
        )

    private data class RuntimeComponents(
        val instanceRegistry: OutboxInstanceRegistry,
        val partitionCoordinator: PartitionCoordinator,
        val processingScheduler: OutboxProcessingScheduler,
        val taskScheduler: TaskScheduler,
    )

    private class RecordingCloseable(
        private val event: String,
        private val events: MutableList<String>,
    ) : AutoCloseable {
        var closeCount = 0
            private set

        override fun close() {
            closeCount++
            events += event
        }
    }

    private companion object {
        val REBALANCE_INTERVAL: Duration = Duration.ofSeconds(10)
    }
}
