package io.namastack.demo

import io.micrometer.tracing.Tracer
import io.micrometer.tracing.propagation.Propagator
import io.namastack.outbox.context.OutboxContextProvider
import org.slf4j.LoggerFactory

/**
 * Captures the current trace context for outbox records using Micrometer Tracing.
 *
 * Uses configured Micrometer Trace Context propagation
 * (e.g., W3C Trace Context format with traceparent, tracestate, and baggage headers) to serialize
 * the current span context into the outbox record's context map during scheduling.
 *
 * The serialized context is stored alongside the outbox record and later restored
 * by [OutboxHandlerTracingAspect] during async processing, maintaining trace continuity
 * across the async boundary.
 *
 * @param tracer Micrometer tracer for accessing current span
 * @param propagator Micrometer propagator for W3C context serialization
 */
class OutboxTracingContextProvider(
    private val tracer: Tracer,
    private val propagator: Propagator,
) : OutboxContextProvider {
    private val log = LoggerFactory.getLogger(OutboxTracingContextProvider::class.java)

    /**
     * Provides trace context from current span for outbox record.
     *
     * Extracts trace context that will be serialized using the configured Micrometer Propagator,
     * typically as W3C Trace Context format (traceparent, tracestate, baggage headers).
     *
     * Returns empty map if no active span exists or trace context cannot be serialized,
     * allowing graceful degradation - outbox processing will continue without tracing.
     *
     * @return Map with W3C Trace Context headers (traceparent, tracestate, baggage), or empty map if no active span
     */
    override fun provide(): Map<String, String> {
        val context = tracer.currentSpan()?.context() ?: return emptyMap()
        val carrier = mutableMapOf<String, String>()

        try {
            propagator.inject(context, carrier) { map, key, value ->
                log.trace("Extracting trace context: {}={}", key, value)
                map!![key] = value
            }
            return carrier
        } catch (ex: Exception) {
            log.warn("Failed to serialize trace context", ex)
            return emptyMap()
        }
    }
}
