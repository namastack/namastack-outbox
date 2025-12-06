package io.namastack.demo

import io.namastack.demo.customer.CustomerRegisteredEvent
import io.namastack.outbox.annotation.OutboxHandler
import io.namastack.outbox.handler.OutboxRecordMetadata
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class DemoOutboxHandler {
    private val logger = LoggerFactory.getLogger(DemoOutboxHandler::class.java)

    @OutboxHandler
    fun handle(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ) {
        logger.info("[Handler] Publish {}: {}", payload::class.simpleName, metadata.key)
        ExternalBroker.publish(payload, metadata.key)
    }

    @OutboxHandler
    fun handle(payload: CustomerRegisteredEvent) {
        logger.info("[Handler] Send email to: {}", payload.email)
        ExternalMailService.send(payload.email)
    }
}
