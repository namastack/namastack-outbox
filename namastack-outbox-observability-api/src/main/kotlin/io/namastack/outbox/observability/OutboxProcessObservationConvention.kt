package io.namastack.outbox.observability

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationConvention

/**
 * Observation convention for outbox record processing observations.
 *
 * Implement this interface to customise the observation name, contextual name, or key values
 * that are attached to every [OutboxObservationDocumentation.OUTBOX_RECORD_PROCESS] observation.
 * The default implementation is
 * [OutboxObservationDocumentation.DefaultOutboxProcessObservationConvention].
 *
 * @author Aleksander Zamojski
 * @since 1.2.0
 */
interface OutboxProcessObservationConvention : ObservationConvention<OutboxProcessObservationContext> {
    /**
     * Returns `true` when [context] is an [OutboxProcessObservationContext], ensuring that this
     * convention is only applied to outbox record processing observations.
     */
    override fun supportsContext(context: Observation.Context): Boolean = context is OutboxProcessObservationContext
}
