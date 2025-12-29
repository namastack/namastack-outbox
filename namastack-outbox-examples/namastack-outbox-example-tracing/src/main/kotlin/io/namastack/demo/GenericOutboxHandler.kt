package io.namastack.demo

import io.micrometer.observation.annotation.Observed
import io.namastack.outbox.handler.OutboxHandler
import io.namastack.outbox.handler.OutboxRecordMetadata
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class GenericOutboxHandler : OutboxHandler {
    private val logger = LoggerFactory.getLogger(GenericOutboxHandler::class.java)

    @Observed(name = "generic.handler")
    override fun handle(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ) {
        logger.info("[Handler] Publish {}: {}", payload::class.simpleName, metadata.key)
        ExternalBroker.publish(payload, metadata.key)
    }
}
