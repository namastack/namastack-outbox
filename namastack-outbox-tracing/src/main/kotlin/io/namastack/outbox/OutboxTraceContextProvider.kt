package io.namastack.outbox

import io.micrometer.tracing.Tracer
import io.micrometer.tracing.propagation.Propagator
import io.namastack.outbox.context.OutboxContextProvider
import org.slf4j.LoggerFactory

class OutboxTraceContextProvider(
    private val tracer: Tracer,
    private val propagator: Propagator,
) : OutboxContextProvider {
    private val log = LoggerFactory.getLogger(OutboxTraceContextProvider::class.java)

    override fun provide(): Map<String, String> {
        val span = tracer.currentSpan() ?: return emptyMap()
        val traceMap = mutableMapOf<String, String>()
        val context = span.context()
        try {
            propagator.inject(context, traceMap) { carrier, key, value ->
                log.trace("Extracting trace context: {}={}", key, value)
                carrier?.put(key, value)
            }
        } catch (ex: Exception) {
            log.error("Failed to serialize trace context", ex)
        }
        return traceMap
    }
}
