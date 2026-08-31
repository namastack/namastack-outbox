package io.namastack.outbox.instrumentation

/**
 * Default [OutboxInstrumentation] that invokes each action without adding behavior.
 *
 * @author Roland Beisel
 * @since 1.10.0
 */
internal object NoOpOutboxInstrumentation : OutboxInstrumentation {
    override fun schedule(
        invocation: OutboxScheduleInvocation,
        action: () -> Unit,
    ) = action()

    override fun process(
        invocation: OutboxProcessInvocation,
        action: () -> Unit,
    ) = action()
}
