package io.namastack.outbox

import io.micrometer.tracing.Link
import io.micrometer.tracing.Span
import io.micrometer.tracing.Tracer
import io.micrometer.tracing.propagation.Propagator
import io.namastack.outbox.context.OutboxContextPropagator
import io.namastack.outbox.context.OutboxContextPropagator.Companion.NoopScope
import io.namastack.outbox.context.OutboxContextPropagator.Scope
import io.namastack.outbox.handler.OutboxHandlerRegistry
import org.slf4j.LoggerFactory

class OutboxTraceContextPropagator(
    private val tracer: Tracer,
    private val propagator: Propagator,
    private val handlerRegistry: OutboxHandlerRegistry,
) : OutboxContextPropagator {
    private val log = LoggerFactory.getLogger(OutboxTraceContextPropagator::class.java)

    override fun openScope(record: OutboxRecord<*>): Scope {
        val span = newSpan(record) ?: return NoopScope
        val scope = tracer.withSpan(span)

        return object : Scope {
            override fun onError(error: Exception) {
                span.error(error)
            }

            override fun close() {
                scope.close()
                span.end()
            }
        }
    }

    private fun newSpan(record: OutboxRecord<*>): Span? {
        val spanBuilder = recreateSpan(record.context) ?: return null

        val handler =
            handlerRegistry.getHandlerById(record.handlerId)
                ?: throw IllegalStateException("No handler with id ${record.handlerId}")

        spanBuilder
            .name("outbox publish")
            .kind(Span.Kind.PRODUCER)
            .tag("outbox.record.key", record.key)
            .tag("outbox.delivery.attempt", record.failureCount + 1L)
            .tag("outbox.handler.class", handler.bean::class.java.name)
            .tag("outbox.handler.method", handler.method.name)

        tracer.currentSpan()?.let { currentSpan -> spanBuilder.addLink(Link(currentSpan)) }

        return spanBuilder.start()
    }

    private fun recreateSpan(context: Map<String, String>): Span.Builder? {
        try {
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
                log.trace("No trace context found in OutboxRecordAttributes")
                return null
            }
            return spanBuilder
        } catch (ex: Exception) {
            log.error("Failed to deserialize trace context", ex)
            return null
        }
    }
}
