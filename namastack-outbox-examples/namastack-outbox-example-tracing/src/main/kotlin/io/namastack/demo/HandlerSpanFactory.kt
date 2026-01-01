package io.namastack.demo

import io.micrometer.tracing.Span
import io.micrometer.tracing.propagation.Propagator
import io.namastack.outbox.handler.OutboxRecordMetadata
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Creates consumer spans for outbox record processing by restoring trace context.
 * Maintains trace continuity across async boundaries.
 */
@Component
class HandlerSpanFactory(
    private val propagator: Propagator,
) {
    private val log = LoggerFactory.getLogger(HandlerSpanFactory::class.java)

    /**
     * Creates a consumer span for processing an outbox record.
     * Returns null if trace context cannot be restored.
     */
    fun create(
        name: String,
        metadata: OutboxRecordMetadata,
    ): Span? {
        val builder = restoreSpan(metadata.context) ?: return null

        return try {
            builder
                .apply {
                    name(name)
                    kind(Span.Kind.CONSUMER)
                    tag("outbox.record.key", metadata.key)
                    tag("outbox.record.createdAt", metadata.createdAt.toString())
                }.start()
        } catch (ex: Exception) {
            log.warn("Failed to create span", ex)
            null
        }
    }

    /**
     * Restores span context from W3C trace headers (traceparent, tracestate, baggage).
     */
    private fun restoreSpan(context: Map<String, String>): Span.Builder? {
        var retrievedTrace = false
        val spanBuilder =
            propagator.extract(context) { carrier, key ->
                carrier[key]?.also {
                    log.trace("Retrieving trace context: {}={}", key, it)
                    retrievedTrace = true
                }
            }

        return spanBuilder.takeIf { retrievedTrace }
    }
}
