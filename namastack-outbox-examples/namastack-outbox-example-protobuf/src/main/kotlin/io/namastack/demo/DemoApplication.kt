package io.namastack.demo

import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication

@SpringBootApplication
class DemoApplication(
    private val orderService: OrderService,
) : CommandLineRunner {
    private val logger = LoggerFactory.getLogger(DemoApplication::class.java)

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            SpringApplication.run(DemoApplication::class.java, *args)
        }
    }

    override fun run(vararg args: String) {
        logger.info("=== Namastack Outbox Protobuf Serializer Demo ===")

        orderService.placeOrder(orderId = "order-1", customerId = "customer-A")
        orderService.shipOrder(orderId = "order-1", destination = "Berlin")

        logger.info("Waiting for processing...")
        Thread.sleep(2000)

        logger.info("=== Demo Complete ===")
    }
}
