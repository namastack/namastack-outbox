package io.namastack.outbox.instrumentation

/**
 * Composes multiple [OutboxInstrumentation] instances into one immutable ordered chain.
 *
 * Instrumentations are invoked in list order, with the first instrumentation acting as the
 * outermost interceptor.
 *
 * @param instrumentations Ordered instrumentations to compose.
 *
 * @author Roland Beisel
 * @since 1.10.0
 */
internal class CompositeOutboxInstrumentation(
    private val instrumentations: List<OutboxInstrumentation>,
) : OutboxInstrumentation {
    /**
     * Instruments one scheduling operation with the complete ordered chain.
     *
     * @param invocation Description of the scheduling operation.
     * @param action Scheduling action to invoke after all interceptors have been entered.
     */
    override fun schedule(
        invocation: OutboxScheduleInvocation,
        action: () -> Unit,
    ) {
        schedule(0, invocation, action)
    }

    /**
     * Instruments one processing operation with the complete ordered chain.
     *
     * @param invocation Description of the processing operation.
     * @param action Handler action to invoke after all interceptors have been entered.
     */
    override fun process(
        invocation: OutboxProcessInvocation,
        action: () -> Unit,
    ) {
        process(0, invocation, action)
    }

    /**
     * Enters the scheduling instrumentation at [index].
     *
     * @param index Index of the next instrumentation to invoke.
     * @param invocation Description of the scheduling operation.
     * @param action Scheduling action at the end of the chain.
     */
    private fun schedule(
        index: Int,
        invocation: OutboxScheduleInvocation,
        action: () -> Unit,
    ) {
        if (index == instrumentations.size) {
            action()
        } else {
            instrumentations[index].schedule(invocation) {
                schedule(index + 1, invocation, action)
            }
        }
    }

    /**
     * Enters the processing instrumentation at [index].
     *
     * @param index Index of the next instrumentation to invoke.
     * @param invocation Description of the processing operation.
     * @param action Handler action at the end of the chain.
     */
    private fun process(
        index: Int,
        invocation: OutboxProcessInvocation,
        action: () -> Unit,
    ) {
        if (index == instrumentations.size) {
            action()
        } else {
            instrumentations[index].process(invocation) {
                process(index + 1, invocation, action)
            }
        }
    }
}
