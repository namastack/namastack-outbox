package io.namastack.demo

import io.namastack.demo.customer.CustomerRegisteredEvent
import io.namastack.outbox.annotation.OutboxHandler
import io.namastack.outbox.annotation.OutboxRetryable
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class CustomerRegisteredOutboxHandler {
    private val logger = LoggerFactory.getLogger(CustomerRegisteredOutboxHandler::class.java)

    @OutboxHandler
    @OutboxRetryable(AggressiveOutboxRetryPolicy::class)
    fun handle(payload: CustomerRegisteredEvent) {
        logger.info("[Handler] Send email to: {}", payload.email)
        ExternalMailService.send(payload.email)
    }
}
