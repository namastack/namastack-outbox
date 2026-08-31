package io.namastack.outbox.instrumentation

/**
 * Composes multiple [OutboxInstrumentation] instances into one immutable ordered chain.
 *
 * Instrumentations are invoked in list order, with the first instrumentation acting as the
 * outermost interceptor.
 *
 * @author Roland Beisel
 * @since 1.10.0
 */
internal class CompositeOutboxInstrumentation(
    private val instrumentations: List<OutboxInstrumentation>,
) : OutboxInstrumentation {
    override fun schedule(
        invocation: OutboxScheduleInvocation,
        action: () -> Unit,
    ) {
        schedule(0, invocation, action)
    }

    override fun process(
        invocation: OutboxProcessInvocation,
        action: () -> Unit,
    ) {
        process(0, invocation, action)
    }

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
