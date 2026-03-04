package io.namastack.demo

import io.namastack.demo.customer.CustomerRegisteredEvent
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.handler.OutboxTypedHandler
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class CustomerRegisteredOutboxHandler : OutboxTypedHandler<CustomerRegisteredEvent> {
    private val logger = LoggerFactory.getLogger(CustomerRegisteredOutboxHandler::class.java)

    override fun handle(
        payload: CustomerRegisteredEvent,
        metadata: OutboxRecordMetadata,
    ) {
        logger.info("[Handler] Send email to: {}", payload.email)
        ExternalMailService.send(payload.email)
    }
}
