package io.namastack.demo

import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.handler.OutboxTypedHandler
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class OrderShippedHandler : OutboxTypedHandler<OrderShippedEvent> {
    private val logger = LoggerFactory.getLogger(OrderShippedHandler::class.java)

    override fun handle(payload: OrderShippedEvent, metadata: OutboxRecordMetadata) {
        logger.info("[Handler] OrderShippedHandler dispatched to: {}", payload.destination)
    }
}
