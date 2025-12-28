package io.namastack.demo

import io.micrometer.tracing.Span
import io.micrometer.tracing.Tracer
import io.namastack.outbox.handler.OutboxHandler
import io.namastack.outbox.handler.OutboxRecordMetadata
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class GenericOutboxHandler(
    private val tracer: Tracer,
) : OutboxHandler {
    private val logger = LoggerFactory.getLogger(GenericOutboxHandler::class.java)

    override fun handle(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ) {
        val span = createSpanFromContext(metadata)

        if (span != null) {
            executeWithTracing(span, payload, metadata)
        } else {
            executeWithoutTracing(payload, metadata)
        }
    }

    private fun createSpanFromContext(metadata: OutboxRecordMetadata): Span? {
        val traceId = metadata.context["traceId"] ?: return null
        val parentSpanId = metadata.context["spanId"] ?: return null

        val parentContext =
            tracer
                .traceContextBuilder()
                .traceId(traceId)
                .spanId(parentSpanId)
                .sampled(true)
                .build()

        return tracer
            .spanBuilder()
            .setParent(parentContext)
            .name("outbox-handler")
            .start()
    }

    private fun executeWithTracing(
        span: Span,
        payload: Any,
        metadata: OutboxRecordMetadata,
    ) {
        try {
            tracer.withSpan(span).use {
                publishPayload(payload, metadata)
            }
        } catch (ex: Exception) {
            span.error(ex)
            throw ex
        } finally {
            span.end()
        }
    }

    private fun executeWithoutTracing(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ) {
        publishPayload(payload, metadata)
    }

    private fun publishPayload(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ) {
        logger.info("[Handler] Publish {}: {}", payload::class.simpleName, metadata.key)
        ExternalBroker.publish(payload, metadata.key)
    }
}
