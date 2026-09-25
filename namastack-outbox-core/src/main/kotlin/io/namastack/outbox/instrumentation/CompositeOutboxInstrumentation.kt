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
     * Instruments one record-processing attempt with the complete ordered chain.
     *
     * @param invocation Description of the record-processing attempt.
     * @param action Processor-chain action to invoke after all interceptors have been entered.
     * @return The unchanged outcome returned by the instrumentation chain.
     */
    override fun processRecord(
        invocation: OutboxRecordProcessingInvocation,
        action: () -> OutboxRecordProcessingOutcome,
    ): OutboxRecordProcessingOutcome = processRecord(0, invocation, action)

    /**
     * Instruments one handler invocation with the complete ordered chain.
     *
     * @param invocation Description of the handler invocation.
     * @param action Handler action to invoke after all interceptors have been entered.
     */
    override fun invokeHandler(
        invocation: OutboxHandlerInvocation,
        action: () -> Unit,
    ) {
        invokeHandler(0, invocation, action)
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
     * Enters the record-processing instrumentation at [index].
     *
     * @param index Index of the next instrumentation to invoke.
     * @param invocation Description of the record-processing attempt.
     * @param action Processor-chain action at the end of the chain.
     * @return The unchanged outcome returned by the instrumentation chain.
     */
    private fun processRecord(
        index: Int,
        invocation: OutboxRecordProcessingInvocation,
        action: () -> OutboxRecordProcessingOutcome,
    ): OutboxRecordProcessingOutcome =
        if (index == instrumentations.size) {
            action()
        } else {
            instrumentations[index].processRecord(invocation) {
                processRecord(index + 1, invocation, action)
            }
        }

    /**
     * Enters the handler instrumentation at [index].
     *
     * @param index Index of the next instrumentation to invoke.
     * @param invocation Description of the handler invocation.
     * @param action Handler action at the end of the chain.
     */
    private fun invokeHandler(
        index: Int,
        invocation: OutboxHandlerInvocation,
        action: () -> Unit,
    ) {
        if (index == instrumentations.size) {
            action()
        } else {
            instrumentations[index].invokeHandler(invocation) {
                invokeHandler(index + 1, invocation, action)
            }
        }
    }
}
