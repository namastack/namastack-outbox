package io.namastack.outbox

import io.micrometer.tracing.Tracer
import io.micrometer.tracing.propagation.Propagator
import io.namastack.outbox.context.OutboxContextProvider

/**
 * Captures the current trace context for outbox records using Micrometer Tracing.
 *
 * Uses W3C Trace Context propagation (traceparent / tracestate headers) to serialize
 * the current span context into the outbox record's context map during scheduling.
 *
 * The serialized context is stored alongside the outbox record and later restored
 * by OutboxHandlerTracingAspect during async processing, maintaining trace continuity
 * across the async boundary.
 *
 * @param tracer Micrometer tracer for accessing current span
 * @param propagator Micrometer propagator for W3C context serialization
 */
class OutboxTracingContextProvider(
    private val tracer: Tracer,
    private val propagator: Propagator,
) : OutboxContextProvider {
    /**
     * Provides trace context from current span for outbox record.
     *
     * Extracts traceId, spanId, and trace flags from the current span and serializes
     * them into W3C Trace Context format (traceparent, tracestate headers).
     *
     * Returns empty map if no active span exists (graceful degradation - outbox
     * processing will continue without tracing).
     *
     * @return Map with W3C Trace Context headers (traceparent, tracestate), or empty map
     */
    override fun provide(): Map<String, String> {
        val context = tracer.currentSpan()?.context() ?: return emptyMap()
        val carrier = mutableMapOf<String, String>()

        propagator.inject(context, carrier) { map, key, value ->
            map!![key] = value
        }

        return carrier
    }
}
