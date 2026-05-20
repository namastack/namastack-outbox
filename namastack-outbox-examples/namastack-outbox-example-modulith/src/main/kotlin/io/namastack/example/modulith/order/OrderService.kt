package io.namastack.example.modulith.order

import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service

@Service
class OrderService(
    private val orderRepository: OrderRepository,
    private val events: ApplicationEventPublisher,
) {
    private val logger = LoggerFactory.getLogger(OrderService::class.java)

    @Transactional
    fun placeOrder(
        sku: String,
        amountCents: Long,
    ): Order {
        logger.info("[Order] Place order for {} cents: {}", amountCents, sku)

        val order = orderRepository.save(Order.place(sku = sku, amountCents = amountCents))

        events.publishEvent(
            OrderPlacedEvent(
                orderId = order.id,
                sku = order.sku,
                amountCents = order.amountCents,
            ),
        )
        logger.info("[Order] Published OrderPlacedEvent for {}", order.id)

        return order
    }
}
