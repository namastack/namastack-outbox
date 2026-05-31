package io.namastack.demo

import io.namastack.outbox.Outbox
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class OrderService(
    private val outbox: Outbox,
) {
    private val logger = LoggerFactory.getLogger(OrderService::class.java)

    @Transactional
    fun placeOrder(orderId: String, customerId: String) {
        logger.info("[Service] Place order: {}", orderId)
        outbox.schedule(OrderCreatedEvent(orderId = orderId, customerId = customerId), orderId)
    }

    @Transactional
    fun shipOrder(orderId: String, destination: String) {
        logger.info("[Service] Ship order: {}", orderId)
        outbox.schedule(OrderShippedEvent(orderId = orderId, destination = destination), orderId)
    }
}
