package io.namastack.outbox.observability

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationConvention

/**
 * Observation convention for complete outbox record-processing attempts.
 *
 * @author Aleksander Zamojski
 * @since 1.10.0
 */
interface OutboxRecordProcessingObservationConvention :
    ObservationConvention<OutboxRecordProcessingObservationContext> {
    /**
     * Returns `true` when [context] is an [OutboxRecordProcessingObservationContext], ensuring that this
     * convention is only applied to outbox record processing observations.
     */
    override fun supportsContext(context: Observation.Context): Boolean =
        context is OutboxRecordProcessingObservationContext
}
