package io.namastack.outbox.instrumentation

/**
 * Instruments scheduling, record processing, and handler invocation at stable outbox boundaries.
 *
 * Implementations are observational around-interceptors. They must invoke action exactly once
 * and propagate its result or failure unchanged.
 *
 * @author Roland Beisel
 * @since 1.10.0
 */
interface OutboxInstrumentation {
    /**
     * Instruments one scheduling operation.
     *
     * @param invocation Description of the scheduling operation.
     * @param action Scheduling action to invoke exactly once.
     */
    fun schedule(
        invocation: OutboxScheduleInvocation,
        action: () -> Unit,
    ) = action()

    /**
     * Instruments one attempt to process a fully materialized record.
     *
     * Implementations must return the exact outcome returned by [action]. If [action] throws,
     * implementations must propagate that same exception unchanged.
     *
     * @param invocation Description of the record-processing attempt.
     * @param action Processor-chain action to invoke exactly once.
     * @return The unchanged outcome returned by [action].
     */
    fun processRecord(
        invocation: OutboxRecordProcessingInvocation,
        action: () -> OutboxRecordProcessingOutcome,
    ): OutboxRecordProcessingOutcome = action()

    /**
     * Instruments one primary or fallback handler invocation.
     *
     * @param invocation Description of the handler invocation.
     * @param action Handler action to invoke exactly once.
     */
    fun invokeHandler(
        invocation: OutboxHandlerInvocation,
        action: () -> Unit,
    ) = action()

    companion object {
        /**
         * Instrumentation that invokes actions without adding behavior.
         */
        @JvmField
        val NOOP: OutboxInstrumentation = object : OutboxInstrumentation {}

        /**
         * Composes [instrumentations] in list order, with the first instrumentation outermost.
         *
         * @param instrumentations Instrumentations to compose.
         * @return A no-op instrumentation for an empty list, the single instance for a one-element
         * list, or an immutable ordered composite for multiple instances.
         */
        @JvmStatic
        fun compose(instrumentations: List<OutboxInstrumentation>): OutboxInstrumentation =
            when (instrumentations.size) {
                0 -> NOOP
                1 -> instrumentations.single()
                else -> CompositeOutboxInstrumentation(instrumentations.toList())
            }
    }
}
