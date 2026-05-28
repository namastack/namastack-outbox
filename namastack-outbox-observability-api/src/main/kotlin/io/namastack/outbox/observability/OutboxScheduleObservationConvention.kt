package io.namastack.outbox.observability

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationConvention

/**
 * Observation convention for outbox record scheduling observations.
 *
 * Implement this interface to customise the observation name, contextual name, or key values
 * that are attached to every [OutboxObservationDocumentation.OUTBOX_RECORD_SCHEDULE] observation.
 * The default implementation is
 * [OutboxObservationDocumentation.DefaultOutboxScheduleObservationConvention].
 *
 * @author Roland Beisel
 * @since 1.3.0
 */
interface OutboxScheduleObservationConvention : ObservationConvention<OutboxScheduleObservationContext> {
    /**
     * Returns `true` when [context] is an [OutboxScheduleObservationContext], ensuring that this
     * convention is only applied to outbox scheduling observations.
     */
    override fun supportsContext(context: Observation.Context): Boolean = context is OutboxScheduleObservationContext
}
