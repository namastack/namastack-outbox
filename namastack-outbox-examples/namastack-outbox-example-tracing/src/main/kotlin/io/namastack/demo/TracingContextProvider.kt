package io.namastack.demo

import io.micrometer.tracing.Tracer
import io.namastack.outbox.context.OutboxContextProvider
import org.springframework.stereotype.Component

/**
 * Captures current trace and span context for outbox records.
 */
@Component
class TracingContextProvider(
    private val tracer: Tracer,
) : OutboxContextProvider {
    override fun provide(): Map<String, String> {
        val span = tracer.currentSpan() ?: return emptyMap()
        val context = span.context()

        return buildMap {
            put("traceId", context.traceId())
            put("spanId", context.spanId())
        }
    }
}
