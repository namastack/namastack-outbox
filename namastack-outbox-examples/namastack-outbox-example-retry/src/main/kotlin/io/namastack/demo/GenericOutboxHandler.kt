package io.namastack.demo

import io.namastack.outbox.handler.OutboxHandler
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.handler.OutboxRetryAware
import io.namastack.outbox.retry.OutboxRetryPolicy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class GenericOutboxHandler(
    private val customOutboxRetryPolicy: OutboxRetryPolicy,
) : OutboxHandler,
    OutboxRetryAware {
    private val logger = LoggerFactory.getLogger(GenericOutboxHandler::class.java)

    override fun handle(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ) {
        logger.info("[Handler] Publish {}: {}", payload::class.simpleName, metadata.key)
        ExternalBroker.publish(payload, metadata.key)
    }

    override fun getRetryPolicy(): OutboxRetryPolicy = customOutboxRetryPolicy
}
