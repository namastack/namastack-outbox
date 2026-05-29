package io.namastack.demo

import io.namastack.demo.customer.CustomerRegisteredEvent
import io.namastack.outbox.annotation.OutboxHandler
import io.namastack.outbox.annotation.OutboxRetryable
import io.namastack.outbox.handler.OutboxRecordMetadata
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class CustomerRegisteredOutboxHandler {
    private val logger = LoggerFactory.getLogger(CustomerRegisteredOutboxHandler::class.java)

    @OutboxHandler
    @OutboxRetryable(AggressiveOutboxRetryPolicy::class)
    fun handle(
        payload: CustomerRegisteredEvent,
        metadata: OutboxRecordMetadata,
    ) {
        logger.info("[Handler] Send email to: {}. Attempt: {}", payload.email, metadata.attempt)
        ExternalMailService.send(payload.email)
    }
}
