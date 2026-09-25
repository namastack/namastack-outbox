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
    override fun supportsContext(context: Observation.Context): Boolean =
        context is OutboxRecordProcessingObservationContext
}
