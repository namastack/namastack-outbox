package io.namastack.outbox.instrumentation

/**
 * Instruments scheduling and processing at the stable outbox operation boundaries.
 *
 * Implementations are observational around-interceptors. They must invoke [action] exactly once
 * and propagate its result or failure unchanged.
 *
 * @author Roland Beisel
 * @since 1.10.0
 */
interface OutboxInstrumentation {
    /**
     * Instruments one scheduling operation.
     */
    fun schedule(
        invocation: OutboxScheduleInvocation,
        action: () -> Unit,
    )

    /**
     * Instruments one primary or fallback handler invocation.
     */
    fun process(
        invocation: OutboxProcessInvocation,
        action: () -> Unit,
    )

    companion object {
        /** Instrumentation that invokes actions without adding behavior. */
        @JvmField
        val NOOP: OutboxInstrumentation = NoOpOutboxInstrumentation

        /**
         * Composes [instrumentations] in list order, with the first instrumentation outermost.
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
