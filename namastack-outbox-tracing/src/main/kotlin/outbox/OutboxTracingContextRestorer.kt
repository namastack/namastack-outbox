package io.namastack.outbox

import io.micrometer.observation.ObservationRegistry
import io.namastack.outbox.handler.OutboxFailureContext
import io.namastack.outbox.handler.OutboxFallbackHandlerInterceptor
import io.namastack.outbox.handler.OutboxHandlerInterceptor
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.observability.OutboxObservationDocumentation
import io.namastack.outbox.observability.OutboxProcessObservationContext
import io.namastack.outbox.observability.OutboxProcessObservationContextCarrier

class OutboxTracingContextRestorer(
    private val observationRegistry: ObservationRegistry,
) : OutboxHandlerInterceptor,
    OutboxFallbackHandlerInterceptor {
    override fun intercept(
        payload: Any,
        metadata: OutboxRecordMetadata,
        next: () -> Unit,
    ) {
        val observation =
            OutboxObservationDocumentation.OUTBOX_RECORD_PROCESS.observation(
                null,
                OutboxObservationDocumentation.DefaultOutboxProcessObservationConvention.INSTANCE,
                { OutboxProcessObservationContext(OutboxProcessObservationContextCarrier(payload, metadata)) },
                observationRegistry,
            )
        observation.observe(next)
    }

    override fun intercept(
        payload: Any,
        context: OutboxFailureContext,
        next: () -> Unit,
    ) {
        // Fallback handler interception
    }
}
