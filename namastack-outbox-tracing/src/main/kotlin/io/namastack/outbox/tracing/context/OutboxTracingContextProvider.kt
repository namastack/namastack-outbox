package io.namastack.outbox.tracing.context

import io.micrometer.tracing.Tracer
import io.micrometer.tracing.propagation.Propagator
import io.namastack.outbox.context.OutboxContextProvider
import org.slf4j.LoggerFactory

/**
 * [OutboxContextProvider] that captures the current trace context so it can be stored alongside
 * the outbox record and restored when the record is processed asynchronously.
 *
 * At **scheduling time**, [provide] serializes the active span context into a map of propagation
 * headers using the configured Micrometer [Propagator] (W3C Trace Context format by default,
 * producing `traceparent`, `tracestate`, and baggage headers). These headers are persisted with
 * the outbox record in the `context` column.
 *
 * At **processing time**, `OutboxInvokerObservationAdvice` creates an
 * `OutboxProcessObservationContext` from the record. Because that context extends
 * [io.micrometer.observation.transport.ReceiverContext], the Micrometer tracing bridge
 * automatically reads the stored headers and creates a child span under the original producer
 * trace, maintaining full end-to-end trace continuity across the async boundary.
 *
 * If no active span exists at scheduling time, or if context serialization fails, [provide]
 * returns an empty map so that record scheduling is never blocked by tracing errors.
 *
 * @param tracer Micrometer [Tracer] used to access the currently active span.
 * @param propagator Micrometer [Propagator] used to inject trace headers into the context map.
 *
 * @author Roland Beisel
 * @since 1.0.0
 */
class OutboxTracingContextProvider(
    private val tracer: Tracer,
    private val propagator: Propagator,
) : OutboxContextProvider {
    private val log = LoggerFactory.getLogger(OutboxTracingContextProvider::class.java)

    /**
     * Serializes the current span context into a propagation-header map.
     *
     * Uses the configured Micrometer [Propagator] to inject the active span context — typically
     * as W3C Trace Context headers (`traceparent`, `tracestate`, and optional baggage).
     *
     * @return A map of propagation headers (e.g. `traceparent`, `tracestate`), or an empty map
     *   if no active span exists or serialization fails.
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
