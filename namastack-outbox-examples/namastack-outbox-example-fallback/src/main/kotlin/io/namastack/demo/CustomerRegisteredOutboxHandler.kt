package io.namastack.demo

import io.namastack.demo.customer.CustomerRegisteredEvent
import io.namastack.outbox.annotation.OutboxFallbackHandler
import io.namastack.outbox.annotation.OutboxHandler
import io.namastack.outbox.handler.OutboxFailureContext
import io.namastack.outbox.handler.OutboxRecordMetadata
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class CustomerRegisteredOutboxHandler {
    private val logger = LoggerFactory.getLogger(CustomerRegisteredOutboxHandler::class.java)

    @OutboxHandler
    @Suppress("UNUSED_PARAMETER")
    fun handle(payload: CustomerRegisteredEvent) {
        logger.info("[Handler] Send email to: {}", payload.email)
        throw RuntimeException("Simulated failure in ExternalMailService")
    }

    @OutboxFallbackHandler
    @Suppress("UNUSED_PARAMETER")
    fun handleFailure(
        payload: CustomerRegisteredEvent,
        context: OutboxFailureContext,
    ) {
        logger.info("[Handler] Invoking fallback method with context $context")
    }
}
