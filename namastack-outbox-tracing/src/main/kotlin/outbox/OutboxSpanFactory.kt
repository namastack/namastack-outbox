package io.namastack.outbox

import io.micrometer.tracing.Link
import io.micrometer.tracing.Span
import io.micrometer.tracing.Tracer
import io.micrometer.tracing.propagation.Propagator
import io.namastack.outbox.handler.OutboxFailureContext
import io.namastack.outbox.handler.OutboxRecordMetadata
import org.slf4j.LoggerFactory

/**
 * Factory for creating child spans from producer spans for outbox record processing.
 *
 * This factory extracts trace context stored in outbox records during creation
 * and creates new spans that are children of the original producer spans. This
 * maintains trace continuity across the async boundary between record creation
 * and processing.
 *
 * The created spans include relevant metadata tags such as record key, handler ID,
 * and delivery attempt count for better observability.
 *
 * @param tracer Micrometer tracer for creating and managing spans
 * @param propagator Micrometer propagator for extracting trace context from records
 */
class OutboxSpanFactory(
    private val tracer: Tracer,
    private val propagator: Propagator,
) {
    private val log = LoggerFactory.getLogger(OutboxSpanFactory::class.java)

    /**
     * Creates a child span for processing an outbox record.
     *
     * Extracts trace context from the record's context map and creates a span
     * that is a child of the original producer span. The span is tagged with
     * record metadata including ID, key, handler class/method, and delivery attempt.
     *
     * If a current span exists, it is linked to the new span to maintain trace
     * relationships. Returns null if trace context cannot be restored (graceful
     * degradation - processing continues without tracing).
     *
     * @param record The outbox record being processed
     * @return Span for the processing span, or null if context restoration fails
     */
    fun createHandlerSpan(metadata: OutboxRecordMetadata): Span? {
        try {
            val spanBuilder = restoreSpan(metadata.context) ?: return null

            spanBuilder
                .name(HANDLER_SPAN_NAME)
                .kind(Span.Kind.CONSUMER)
                .tag(KEY_TAG, metadata.key)
                .tag(HANDLER_ID_TAG, metadata.handlerId)
                .tag(DELIVERY_ATTEMPT_TAG, metadata.failureCount + 1L)

            tracer.currentSpan()?.let { currentSpan -> spanBuilder.addLink(Link(currentSpan)) }

            return spanBuilder.start()
        } catch (ex: Exception) {
            log.warn("Failed to create span", ex)
            return null
        }
    }

    fun createFallbackHandlerSpan(context: OutboxFailureContext): Span? {
        try {
            val spanBuilder = restoreSpan(context.context) ?: return null

            spanBuilder
                .name(FALLBACK_HANDLER_SPAN_NAME)
                .kind(Span.Kind.CONSUMER)
                .tag(KEY_TAG, context.recordKey)

            tracer.currentSpan()?.let { currentSpan -> spanBuilder.addLink(Link(currentSpan)) }

            return spanBuilder.start()
        } catch (ex: Exception) {
            log.warn("Failed to create span", ex)
            return null
        }
    }

    /**
     * Restores producer span context from outbox record's context map and creates a child span builder.
     *
     * Extracts Trace Context headers (e.g., W3C headers - traceparent, tracestate, baggage) that were
     * stored during record creation and reconstructs the producer span context. The propagator is used
     * to deserialize the trace context back into a span builder.
     *
     * @param context Context map from the outbox record containing trace headers
     * @return Span builder with restored trace context, or null if no valid context found
     */
    private fun restoreSpan(context: Map<String, String>): Span.Builder? {
        var retrievedTrace = false
        val spanBuilder =
            propagator.extract(context) { carrier, key ->
                val value = carrier[key]
                if (value != null) {
                    log.trace("Retrieving trace context: {}={}", key, value)
                    retrievedTrace = true
                }
                value
            }

        if (!retrievedTrace) {
            log.trace("No trace context found")
            return null
        }
        return spanBuilder
    }

    private companion object {
        private const val HANDLER_SPAN_NAME = "outbox process"
        private const val FALLBACK_HANDLER_SPAN_NAME = "outbox fallback process"
        private const val KEY_TAG = "outbox.record.key"
        private const val HANDLER_ID_TAG = "outbox.handler.id"
        private const val DELIVERY_ATTEMPT_TAG = "outbox.delivery.attempt"
    }
}
