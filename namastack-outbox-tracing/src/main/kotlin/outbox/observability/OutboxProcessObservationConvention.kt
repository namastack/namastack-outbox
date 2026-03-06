package io.namastack.outbox.observability

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationConvention

interface OutboxProcessObservationConvention : ObservationConvention<OutboxProcessObservationContext> {
    override fun supportsContext(context: Observation.Context): Boolean = context is OutboxProcessObservationContext
}
