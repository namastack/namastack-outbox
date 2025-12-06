package io.namastack.demo

import io.namastack.demo.customer.CustomerRegisteredEvent
import io.namastack.outbox.handler.OutboxTypedHandler
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class CustomerRegisteredOutboxHandler : OutboxTypedHandler<CustomerRegisteredEvent> {
    private val logger = LoggerFactory.getLogger(CustomerRegisteredOutboxHandler::class.java)

    override fun handle(payload: CustomerRegisteredEvent) {
        logger.info("[Handler] Send email to: {}", payload.email)
        ExternalMailService.send(payload.email)
    }
}
