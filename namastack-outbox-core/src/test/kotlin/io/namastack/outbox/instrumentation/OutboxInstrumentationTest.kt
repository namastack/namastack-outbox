package io.namastack.outbox.instrumentation

import io.namastack.outbox.OutboxRecordTestFactory.outboxRecord
import io.namastack.outbox.instrumentation.OutboxHandlerKind.FALLBACK
import io.namastack.outbox.instrumentation.OutboxRecordProcessingOutcome.COMPLETED
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
    fun `NOOP executes handler action exactly once`() {
        var invocations = 0

        OutboxInstrumentation.NOOP.invokeHandler(handlerInvocation()) {
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
    fun `schedule-only instrumentation uses default handler implementation`() {
        var invocations = 0
        val instrumentation =
            object : OutboxInstrumentation {
                override fun schedule(
                    invocation: OutboxScheduleInvocation,
                    action: () -> Unit,
                ) = action()
            }

        instrumentation.invokeHandler(handlerInvocation()) {
            invocations++
        }

        assertThat(invocations).isEqualTo(1)
    }

    @Test
    fun `handler-only instrumentation uses default schedule implementation`() {
        var invocations = 0
        val instrumentation =
            object : OutboxInstrumentation {
                override fun invokeHandler(
                    invocation: OutboxHandlerInvocation,
                    action: () -> Unit,
                ) = action()
            }

        instrumentation.schedule(scheduleInvocation()) {
            invocations++
        }

        assertThat(invocations).isEqualTo(1)
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
    fun `handler invocation retains supplied operation data`() {
        val record = outboxRecord(recordKey = "order-42")

        val invocation =
            OutboxHandlerInvocation(
                record = record,
                handlerKind = FALLBACK,
                channel = "orders",
            )

        assertThat(invocation.record).isSameAs(record)
        assertThat(invocation.handlerKind).isEqualTo(FALLBACK)
        assertThat(invocation.channel).isEqualTo("orders")
    }

    @Test
    fun `NOOP returns record processing outcome`() {
        val actual =
            OutboxInstrumentation.NOOP.processRecord(recordProcessingInvocation()) {
                COMPLETED
            }

        assertThat(actual).isEqualTo(COMPLETED)
    }

    @Test
    fun `composition invokes record processing instrumentations in nesting order and retains outcome`() {
        val calls = mutableListOf<String>()
        val composite =
            OutboxInstrumentation.compose(
                listOf(
                    recordingInstrumentation("first", calls),
                    recordingInstrumentation("second", calls),
                ),
            )

        val actual =
            composite.processRecord(recordProcessingInvocation()) {
                calls += "action"
                COMPLETED
            }

        assertThat(actual).isEqualTo(COMPLETED)
        assertThat(calls).containsExactly(
            "first-before",
            "second-before",
            "action",
            "second-after",
            "first-after",
        )
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
    fun `composition invokes handler instrumentations in nesting order`() {
        val calls = mutableListOf<String>()
        val composite =
            OutboxInstrumentation.compose(
                listOf(
                    recordingInstrumentation("first", calls),
                    recordingInstrumentation("second", calls),
                ),
            )

        composite.invokeHandler(handlerInvocation()) {
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
            composite.invokeHandler(handlerInvocation()) {
                throw failure
            }
        }.isSameAs(failure)
        assertThat(calls).containsExactly("second-error", "first-error")
    }

    @Test
    fun `record processing composition propagates original exception through every instrumentation`() {
        val calls = mutableListOf<String>()
        val failure = IllegalStateException("failed")
        val composite =
            OutboxInstrumentation.compose(
                listOf(
                    errorRecordingRecordProcessingInstrumentation("first", calls),
                    errorRecordingRecordProcessingInstrumentation("second", calls),
                ),
            )

        assertThatThrownBy {
            composite.processRecord(recordProcessingInvocation()) {
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

    private fun handlerInvocation() =
        OutboxHandlerInvocation(
            record = outboxRecord(),
            handlerKind = FALLBACK,
            channel = "orders",
        )

    private fun recordProcessingInvocation() =
        OutboxRecordProcessingInvocation(
            record = outboxRecord(),
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
        handlerAction = { _, action ->
            calls += "$name-before"
            action()
            calls += "$name-after"
        },
        recordProcessingAction = { _, action ->
            calls += "$name-before"
            action().also {
                calls += "$name-after"
            }
        },
    )

    private fun errorRecordingInstrumentation(
        name: String,
        calls: MutableList<String>,
    ) = instrumentation(
        handlerAction = { _, action ->
            try {
                action()
            } catch (failure: Throwable) {
                calls += "$name-error"
                throw failure
            }
        },
    )

    private fun errorRecordingRecordProcessingInstrumentation(
        name: String,
        calls: MutableList<String>,
    ) = instrumentation(
        recordProcessingAction = { _, action ->
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
        handlerAction: (OutboxHandlerInvocation, () -> Unit) -> Unit = { _, action -> action() },
        recordProcessingAction: (OutboxRecordProcessingInvocation, () -> OutboxRecordProcessingOutcome) ->
        OutboxRecordProcessingOutcome = { _, action -> action() },
    ): OutboxInstrumentation =
        object : OutboxInstrumentation {
            override fun schedule(
                invocation: OutboxScheduleInvocation,
                action: () -> Unit,
            ) = scheduleAction(invocation, action)

            override fun invokeHandler(
                invocation: OutboxHandlerInvocation,
                action: () -> Unit,
            ) = handlerAction(invocation, action)

            override fun processRecord(
                invocation: OutboxRecordProcessingInvocation,
                action: () -> OutboxRecordProcessingOutcome,
            ): OutboxRecordProcessingOutcome = recordProcessingAction(invocation, action)
        }
}
