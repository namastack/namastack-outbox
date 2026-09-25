package io.namastack.outbox.observability

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationConvention

/**
 * Observation convention for primary and fallback handler invocations.
 *
 * Implement this interface to customise the observation name, contextual name, or key values
 * attached to every [OutboxObservationDocumentation.OUTBOX_RECORD_PROCESS] observation.
 * The default implementation is
 * [OutboxObservationDocumentation.DefaultOutboxHandlerObservationConvention].
 *
 * @author Aleksander Zamojski
 * @since 1.10.0
 */
interface OutboxHandlerObservationConvention : ObservationConvention<OutboxHandlerObservationContext> {
    /** Returns `true` when this convention supports the supplied handler observation context. */
    override fun supportsContext(context: Observation.Context): Boolean = context is OutboxHandlerObservationContext
}
