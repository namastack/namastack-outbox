package io.namastack.demo

import io.namastack.demo.customer.CustomerRegisteredEvent
import io.namastack.demo.customer.CustomerRemovedEvent
import io.namastack.outbox.annotation.OutboxHandler
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class DemoOutboxHandler(
    private val externalService: ExternalService,
) {
    private val logger = LoggerFactory.getLogger(DemoOutboxHandler::class.java)

    @OutboxHandler
    fun handle(payload: CustomerRegisteredEvent) {
        logger.info("[Handler] Get CustomerRegisteredEvent")
        externalService.removeCustomer(payload)
    }

    @OutboxHandler
    fun handle(payload: CustomerRemovedEvent) {
        logger.info("[Handler] Get CustomerRemovedEvent")
        ExternalBroker.publish(payload)
    }
}
