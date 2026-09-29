package io.namastack.outbox.runtime

import io.mockk.mockk
import io.namastack.outbox.Outbox
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.context.Lifecycle

class OutboxRuntimeTest {
    @Test
    fun `starts once in dependency order and closes once in reverse order`() {
        val events = mutableListOf<String>()
        val runtime =
            OutboxRuntime(
                outbox = mockk<Outbox>(),
                lifecycleComponents =
                    listOf(
                        RecordingLifecycle("instance", events),
                        RecordingLifecycle("rebalance", events),
                        RecordingLifecycle("processing", events),
                    ),
            )

        assertThat(runtime.isRunning()).isFalse()

        runtime.start()
        runtime.start()

        assertThat(runtime.isRunning()).isTrue()
        assertThat(events).containsExactly("instance.start", "rebalance.start", "processing.start")

        runtime.close()
        runtime.close()

        assertThat(runtime.isRunning()).isFalse()
        assertThat(events)
            .containsExactly(
                "instance.start",
                "rebalance.start",
                "processing.start",
                "processing.stop",
                "rebalance.stop",
                "instance.stop",
            )
    }

    @Test
    fun `rolls back successfully started components when startup fails`() {
        val events = mutableListOf<String>()
        val failure = IllegalStateException("processing failed")
        val runtime =
            OutboxRuntime(
                outbox = mockk<Outbox>(),
                lifecycleComponents =
                    listOf(
                        RecordingLifecycle("instance", events),
                        RecordingLifecycle("rebalance", events),
                        RecordingLifecycle("processing", events, failure),
                    ),
            )

        assertThatThrownBy(runtime::start).isSameAs(failure)

        assertThat(events)
            .containsExactly(
                "instance.start",
                "rebalance.start",
                "processing.start",
                "rebalance.stop",
                "instance.stop",
            )
        assertThat(runtime.isRunning()).isFalse()
        assertThatThrownBy(runtime::start)
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Outbox runtime is already closed")
    }

    @Test
    fun `closing a stopped runtime prevents later startup`() {
        val runtime = OutboxRuntime(mockk<Outbox>(), emptyList())

        runtime.close()

        assertThatThrownBy(runtime::start)
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Outbox runtime is already closed")
    }

    private class RecordingLifecycle(
        private val name: String,
        private val events: MutableList<String>,
        private val startFailure: Throwable? = null,
    ) : Lifecycle {
        private var running = false

        override fun isRunning(): Boolean = running

        override fun start() {
            events += "$name.start"
            startFailure?.let { throw it }
            running = true
        }

        override fun stop() {
            events += "$name.stop"
            running = false
        }
    }
}
