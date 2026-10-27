package io.namastack.example.delivery

import io.namastack.example.delivery.orders.OrderService
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["demo.enabled"], havingValue = "true", matchIfMissing = true)
class DemoRunner(
    private val orders: OrderService,
) : CommandLineRunner {
    override fun run(vararg args: String) {
        val orderId = orders.placeOrder("Spring Modulith book", 4990)
        LoggerFactory.getLogger(javaClass).info("Order {} committed; Namastack will deliver its events", orderId)
    }
}
