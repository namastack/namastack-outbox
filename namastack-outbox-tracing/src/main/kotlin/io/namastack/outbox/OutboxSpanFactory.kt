package io.namastack.outbox

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.context.propagation.TextMapPropagator

/**
 * Factory for creating OpenTelemetry consumer spans for outbox record processing.
 *
 * Creates new root spans with links to producer spans, following OpenTelemetry
 * semantic conventions for asynchronous queue-based messaging. Uses W3C Trace Context
 * propagation to restore producer context from outbox record metadata.
 *
 * @param tracer OpenTelemetry tracer for creating spans
 * @param propagator Text map propagator for extracting trace context
 */
class OutboxSpanFactory(
    private val tracer: Tracer,
    private val propagator: TextMapPropagator,
) {
    /**
     * Creates a consumer span for processing an outbox record.
     *
     * Creates a new root span (no parent) with a link to the original producer span.
     * Returns null if no valid producer context can be restored from the record.
     *
     * @param record Outbox record containing trace context in its context map
     * @return Consumer span with link to producer, or null if no valid context found
     */
    fun create(record: OutboxRecord<*>): Span? {
        val producerSpanContext = restoreProducerSpanContext(record) ?: return null

        return tracer
            .spanBuilder(SPAN_NAME)
            .setSpanKind(SpanKind.CONSUMER)
            .setNoParent()
            .addLink(producerSpanContext)
            .setAllAttributes(record.toAttributes())
            .startSpan()
    }

    /**
     * Restores producer span context from outbox record's context map.
     *
     * Extracts W3C Trace Context headers (traceparent, tracestate) that were
     * stored during record creation and reconstructs the producer span context.
     *
     * @param record Outbox record with trace context in context map
     * @return Producer span context, or null if extraction fails or context is invalid
     */
    private fun restoreProducerSpanContext(record: OutboxRecord<*>): SpanContext? {
        val extractedContext =
            propagator.extract(
                Context.root(),
                record.context,
                OutboxTextMapGetter,
            )

        val producerSpan = Span.fromContext(extractedContext)
        if (!producerSpan.spanContext.isValid) return null

        return producerSpan.spanContext
    }

    /**
     * TextMapGetter for extracting trace context from outbox record context map.
     */
    private object OutboxTextMapGetter : TextMapGetter<Map<String, String>> {
        override fun keys(carrier: Map<String, String>) = carrier.keys

        override fun get(
            carrier: Map<String, String>?,
            key: String,
        ) = carrier?.get(key)
    }

    private companion object {
        private const val SPAN_NAME = "process outbox record"
    }
}
