package io.namastack.outbox

import io.micrometer.tracing.Link
import io.micrometer.tracing.Span
import io.micrometer.tracing.Tracer
import io.micrometer.tracing.propagation.Propagator
import io.namastack.outbox.interceptor.OutboxCreationInterceptor
import io.namastack.outbox.interceptor.OutboxDeliveryInterceptor
import io.namastack.outbox.interceptor.OutboxDeliveryInterceptorContext
import org.slf4j.LoggerFactory

class OutboxTraceContextPropagator(
    private val tracer: Tracer,
    private val propagator: Propagator,
) : OutboxCreationInterceptor,
    OutboxDeliveryInterceptor {
    private val log = LoggerFactory.getLogger(OutboxTraceContextPropagator::class.java)

    override fun beforePersist(attributes: MutableMap<String, String>) {
        val span = tracer.currentSpan() ?: return
        val context = span.context()
        try {
            propagator.inject(context, attributes) { carrier, key, value ->
                log.trace("Extracting trace context: {}={}", key, value)
                carrier?.put(key, value)
            }
        } catch (ex: Exception) {
            log.error("Failed to serialize trace context", ex)
        }
    }

    override fun beforeHandler(context: OutboxDeliveryInterceptorContext) {
        val spanBuilder = recreateSpan(context.attributes) ?: return

        spanBuilder
            .name("outbox publish")
            .kind(Span.Kind.PRODUCER)
            .tag("outbox.record.key", context.key)
            .tag("outbox.delivery.attempt", context.failureCount + 1L)
            .tag("outbox.handler.class", context.handlerClass.name)
            .tag("outbox.handler.method", context.handlerMethod.name)

        tracer.currentSpan()?.let { currentSpan -> spanBuilder.addLink(Link(currentSpan)) }

        val span = spanBuilder.start()
        val scope = tracer.withSpan(span)
        context.put("tracingScope", scope)
        context.put("tracingSpan", span)
    }

    override fun onError(
        context: OutboxDeliveryInterceptorContext,
        error: Exception,
    ) {
        context.get<Span>("tracingSpan")?.error(error)
    }

    override fun afterCompletion(context: OutboxDeliveryInterceptorContext) {
        context.get<Tracer.SpanInScope>("tracingScope")?.close()
        context.get<Span>("tracingSpan")?.end()
    }

    private fun recreateSpan(attributes: Map<String, String>): Span.Builder? {
        try {
            var retrievedTrace = false
            val spanBuilder =
                propagator.extract(attributes) { carrier, key ->
                    val value = carrier[key]
                    if (value != null) {
                        log.trace("Retrieving trace context: {}={}", key, value)
                        retrievedTrace = true
                    }
                    value
                }

            if (!retrievedTrace) {
                log.debug("No trace context found in OutboxRecordAttributes")
                return null
            }
            return spanBuilder
        } catch (ex: Exception) {
            log.error("Failed to deserialize trace context", ex)
            return null
        }
    }
}
