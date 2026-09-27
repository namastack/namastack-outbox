package io.namastack.outbox.observability

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationConvention

/**
 * Observation convention for primary and fallback handler invocations.
 *
 * Implement this interface to customise the observation name, contextual name, or key values
 * attached to every [OutboxObservationDocumentation.OUTBOX_RECORD_PROCESS] observation.
 * The default implementation is
 * [OutboxObservationDocumentation.DefaultOutboxProcessObservationConvention].
 *
 * @author Aleksander Zamojski
 * @since 1.2.0
 */
interface OutboxProcessObservationConvention : ObservationConvention<OutboxProcessObservationContext> {
    /**
     * Returns `true` when [context] is an [OutboxProcessObservationContext], ensuring that this
     * convention is only applied to primary and fallback handler invocation observations.
     */
    override fun supportsContext(context: Observation.Context): Boolean = context is OutboxProcessObservationContext
}
