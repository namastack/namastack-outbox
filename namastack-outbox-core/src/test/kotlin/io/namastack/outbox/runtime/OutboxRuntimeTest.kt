package io.namastack.outbox.runtime

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

class OutboxRuntimeTest {
    @Test
    fun `starts in dependency order and ignores repeated start`() {
        val events = mutableListOf<String>()
        val components = components(events)
        val runtime = runtime(components)

        runtime.start()
        runtime.start()

        assertThat(events)
            .containsExactly("instance.start", "coordinator.start", "processing.start")
        assertThat(runtime.isRunning()).isTrue()
        verify(exactly = 1) { components.instanceRegistry.start() }
        verify(exactly = 1) { components.partitionCoordinator.start() }
        verify(exactly = 1) { components.processingScheduler.start() }
    }

    @Test
    fun `closes owned resources in dependency order once`() {
        val events = mutableListOf<String>()
        val components = components(events)
        val persistenceResource = RecordingCloseable("persistence.close", events)
        val threadingResource = RecordingCloseable("threading.close", events)
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
                "coordinator.stop",
                "instance.stop",
                "threading.close",
                "persistence.close",
            )
        assertThat(runtime.isRunning()).isFalse()
        assertThat(persistenceResource.closeCount).isEqualTo(1)
        assertThat(threadingResource.closeCount).isEqualTo(1)
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
                "coordinator.start",
                "processing.start",
                "coordinator.stop",
                "instance.stop",
                "threading.close",
                "persistence.close",
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

        every { instanceRegistry.start() } answers { events += "instance.start" }
        every { instanceRegistry.isRunning } returns true
        every { instanceRegistry.stop() } answers { events += "instance.stop" }
        every { partitionCoordinator.start() } answers { events += "coordinator.start" }
        every { partitionCoordinator.isRunning } returns true
        every { partitionCoordinator.stop() } answers { events += "coordinator.stop" }
        every { processingScheduler.start() } answers { events += "processing.start" }
        every { processingScheduler.isRunning } returns true
        every { processingScheduler.stop() } answers { events += "processing.stop" }

        return RuntimeComponents(
            instanceRegistry = instanceRegistry,
            partitionCoordinator = partitionCoordinator,
            processingScheduler = processingScheduler,
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
            ownedPersistenceResources = persistenceResources,
            ownedThreadingResources = threadingResources,
        )

    private data class RuntimeComponents(
        val instanceRegistry: OutboxInstanceRegistry,
        val partitionCoordinator: PartitionCoordinator,
        val processingScheduler: OutboxProcessingScheduler,
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
}
