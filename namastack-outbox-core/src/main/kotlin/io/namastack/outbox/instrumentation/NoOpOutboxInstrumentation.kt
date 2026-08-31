package io.namastack.outbox.instrumentation

/**
 * Default [OutboxInstrumentation] that invokes each action without adding behavior.
 *
 * @author Roland Beisel
 * @since 1.10.0
 */
internal object NoOpOutboxInstrumentation : OutboxInstrumentation {
    /**
     * Invokes the scheduling action without adding behavior.
     *
     * @param invocation Description of the scheduling operation.
     * @param action Scheduling action to invoke.
     */
    override fun schedule(
        invocation: OutboxScheduleInvocation,
        action: () -> Unit,
    ) = action()

    /**
     * Invokes the processing action without adding behavior.
     *
     * @param invocation Description of the processing operation.
     * @param action Handler action to invoke.
     */
    override fun process(
        invocation: OutboxProcessInvocation,
        action: () -> Unit,
    ) = action()
}
