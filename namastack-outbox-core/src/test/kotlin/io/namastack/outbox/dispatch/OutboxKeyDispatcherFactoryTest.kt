package io.namastack.outbox.dispatch

import io.mockk.mockk
import io.namastack.outbox.runtime.OutboxRuntimeSettings
import io.namastack.outbox.runtime.OutboxRuntimeSettings.Processing.DispatchMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.task.SyncTaskExecutor
import java.time.Clock

class OutboxKeyDispatcherFactoryTest {
    @Test
    fun `creates batch key dispatcher for batch mode`() {
        assertThat(create(DispatchMode.BATCH)).isInstanceOf(BatchKeyDispatcher::class.java)
    }

    @Test
    fun `creates continuous key dispatcher for continuous mode`() {
        val dispatcher = create(DispatchMode.CONTINUOUS)

        assertThat(dispatcher).isInstanceOf(ContinuousKeyDispatcher::class.java)
        assertThat(dispatcher.reportsPollResults).isTrue()
    }

    @Test
    fun `batch key dispatcher leaves poll result reporting to the scheduler`() {
        assertThat(create(DispatchMode.BATCH).reportsPollResults).isFalse()
    }

    private fun create(mode: DispatchMode): KeyDispatcher =
        OutboxKeyDispatcherFactory.create(
            settings = OutboxRuntimeSettings(processing = OutboxRuntimeSettings.Processing(dispatchMode = mode)),
            trigger = mockk(relaxed = true),
            recordRepository = mockk(relaxed = true),
            recordProcessorChainInvoker = mockk(relaxed = true),
            taskExecutor = SyncTaskExecutor(),
            clock = Clock.systemUTC(),
        )
}
