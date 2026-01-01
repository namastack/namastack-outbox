package io.namastack.demo

import io.micrometer.tracing.Tracer
import io.namastack.outbox.handler.OutboxHandler
import io.namastack.outbox.handler.OutboxRecordMetadata
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class GenericOutboxHandler(
    private val handlerSpanFactory: HandlerSpanFactory,
    private val tracer: Tracer,
) : OutboxHandler {
    private val logger = LoggerFactory.getLogger(GenericOutboxHandler::class.java)

    override fun handle(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ) {
        val span =
            handlerSpanFactory.create("publish payload", metadata)
                ?: throw IllegalStateException("Could not create span.")

        tracer.runWithSpan(span) {
            logger.info("[Handler] Publish {}: {}", payload::class.simpleName, metadata.key)
            ExternalBroker.publish(payload, metadata.key)
        }
    }
}
