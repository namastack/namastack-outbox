package io.namastack.demo

import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.handler.OutboxTypedHandler
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class OrderCreatedHandler : OutboxTypedHandler<OrderCreatedEvent> {
    private val logger = LoggerFactory.getLogger(OrderCreatedHandler::class.java)

    override fun handle(payload: OrderCreatedEvent, metadata: OutboxRecordMetadata) {
        logger.info("[Handler] OrderCreatedHandler received order: {}", payload.orderId)
    }
}
