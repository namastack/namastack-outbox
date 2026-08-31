package io.namastack.outbox.instrumentation

import io.namastack.outbox.OutboxRecordTestFactory.outboxRecord
import io.namastack.outbox.instrumentation.OutboxProcessHandlerKind.FALLBACK
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class OutboxInstrumentationTest {
    @Test
    fun `NOOP executes schedule action exactly once`() {
        var invocations = 0

        OutboxInstrumentation.NOOP.schedule(scheduleInvocation()) {
            invocations++
        }

        assertThat(invocations).isEqualTo(1)
    }

    @Test
    fun `NOOP executes process action exactly once`() {
        var invocations = 0

        OutboxInstrumentation.NOOP.process(processInvocation()) {
            invocations++
        }

        assertThat(invocations).isEqualTo(1)
    }

    @Test
    fun `NOOP propagates original exception`() {
        val failure = IllegalStateException("failed")

        assertThatThrownBy {
            OutboxInstrumentation.NOOP.schedule(scheduleInvocation()) {
                throw failure
            }
        }.isSameAs(failure)
    }

    @Test
    fun `schedule invocation retains supplied operation data`() {
        val payload = Any()

        val invocation =
            OutboxScheduleInvocation(
                payload = payload,
                recordKey = "order-42",
                channel = "orders",
            )

        assertThat(invocation.payload).isSameAs(payload)
        assertThat(invocation.recordKey).isEqualTo("order-42")
        assertThat(invocation.channel).isEqualTo("orders")
    }

    @Test
    fun `process invocation retains supplied operation data`() {
        val record = outboxRecord(recordKey = "order-42")

        val invocation =
            OutboxProcessInvocation(
                record = record,
                handlerKind = FALLBACK,
                channel = "orders",
            )

        assertThat(invocation.record).isSameAs(record)
        assertThat(invocation.handlerKind).isEqualTo(FALLBACK)
        assertThat(invocation.channel).isEqualTo("orders")
    }

    @Test
    fun `empty composition returns NOOP`() {
        assertThat(OutboxInstrumentation.compose(emptyList())).isSameAs(OutboxInstrumentation.NOOP)
    }

    @Test
    fun `single composition returns supplied instrumentation`() {
        val instrumentation = instrumentation()

        assertThat(OutboxInstrumentation.compose(listOf(instrumentation))).isSameAs(instrumentation)
    }

    @Test
    fun `composition invokes schedule instrumentations in nesting order`() {
        val calls = mutableListOf<String>()
        val composite =
            OutboxInstrumentation.compose(
                listOf(
                    recordingInstrumentation("first", calls),
                    recordingInstrumentation("second", calls),
                ),
            )

        composite.schedule(scheduleInvocation()) {
            calls += "action"
        }

        assertThat(calls).containsExactly(
            "first-before",
            "second-before",
            "action",
            "second-after",
            "first-after",
        )
    }

    @Test
    fun `composition invokes process instrumentations in nesting order`() {
        val calls = mutableListOf<String>()
        val composite =
            OutboxInstrumentation.compose(
                listOf(
                    recordingInstrumentation("first", calls),
                    recordingInstrumentation("second", calls),
                ),
            )

        composite.process(processInvocation()) {
            calls += "action"
        }

        assertThat(calls).containsExactly(
            "first-before",
            "second-before",
            "action",
            "second-after",
            "first-after",
        )
    }

    @Test
    fun `composition snapshots supplied list`() {
        val calls = mutableListOf<String>()
        val instrumentations =
            mutableListOf(
                recordingInstrumentation("first", calls),
                recordingInstrumentation("second", calls),
            )
        val composite = OutboxInstrumentation.compose(instrumentations)
        instrumentations.clear()

        composite.schedule(scheduleInvocation()) {
            calls += "action"
        }

        assertThat(calls).containsExactly(
            "first-before",
            "second-before",
            "action",
            "second-after",
            "first-after",
        )
    }

    @Test
    fun `composition propagates original exception through every instrumentation`() {
        val calls = mutableListOf<String>()
        val failure = IllegalStateException("failed")
        val composite =
            OutboxInstrumentation.compose(
                listOf(
                    errorRecordingInstrumentation("first", calls),
                    errorRecordingInstrumentation("second", calls),
                ),
            )

        assertThatThrownBy {
            composite.process(processInvocation()) {
                throw failure
            }
        }.isSameAs(failure)
        assertThat(calls).containsExactly("second-error", "first-error")
    }

    private fun scheduleInvocation() =
        OutboxScheduleInvocation(
            payload = Any(),
            recordKey = "order-42",
            channel = "orders",
        )

    private fun processInvocation() =
        OutboxProcessInvocation(
            record = outboxRecord(),
            handlerKind = FALLBACK,
            channel = "orders",
        )

    private fun recordingInstrumentation(
        name: String,
        calls: MutableList<String>,
    ) = instrumentation(
        scheduleAction = { _, action ->
            calls += "$name-before"
            action()
            calls += "$name-after"
        },
        processAction = { _, action ->
            calls += "$name-before"
            action()
            calls += "$name-after"
        },
    )

    private fun errorRecordingInstrumentation(
        name: String,
        calls: MutableList<String>,
    ) = instrumentation(
        processAction = { _, action ->
            try {
                action()
            } catch (failure: Throwable) {
                calls += "$name-error"
                throw failure
            }
        },
    )

    private fun instrumentation(
        scheduleAction: (OutboxScheduleInvocation, () -> Unit) -> Unit = { _, action -> action() },
        processAction: (OutboxProcessInvocation, () -> Unit) -> Unit = { _, action -> action() },
    ): OutboxInstrumentation =
        object : OutboxInstrumentation {
            override fun schedule(
                invocation: OutboxScheduleInvocation,
                action: () -> Unit,
            ) = scheduleAction(invocation, action)

            override fun process(
                invocation: OutboxProcessInvocation,
                action: () -> Unit,
            ) = processAction(invocation, action)
        }
}
