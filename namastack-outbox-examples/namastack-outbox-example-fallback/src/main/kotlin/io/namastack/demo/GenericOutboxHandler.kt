package io.namastack.demo

import io.namastack.outbox.handler.OutboxFailureContext
import io.namastack.outbox.handler.OutboxHandlerWithFallback
import io.namastack.outbox.handler.OutboxRecordMetadata
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class GenericOutboxHandler : OutboxHandlerWithFallback {
    private val logger = LoggerFactory.getLogger(GenericOutboxHandler::class.java)

    override fun handle(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ) {
        logger.info("[Handler] Publish {}: {}", payload::class.simpleName, metadata.key)
        throw RuntimeException("Simulated failure in ExternalBroker")
    }

    override fun handleFailure(
        payload: Any,
        metadata: OutboxRecordMetadata,
        context: OutboxFailureContext,
    ) {
        logger.info("[Handler] Invoking fallback method with context $context")
    }
}
