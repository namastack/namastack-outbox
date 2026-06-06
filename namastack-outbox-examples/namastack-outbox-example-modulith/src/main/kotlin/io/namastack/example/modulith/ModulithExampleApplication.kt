package io.namastack.example.modulith

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import io.namastack.example.modulith.order.OrderService
import io.namastack.example.modulith.order.PlaceOrderCommand
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication

@SpringBootApplication
class ModulithExampleApplication(
    private val orderService: OrderService,
    private val observationRegistry: ObservationRegistry,
) : CommandLineRunner {
    private val logger = LoggerFactory.getLogger(ModulithExampleApplication::class.java)

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            SpringApplication.run(ModulithExampleApplication::class.java, *args)
        }
    }

    override fun run(vararg args: String) {
        logger.info("=== Spring Modulith Kafka Demo ===")

        Observation
            .createNotStarted("modulith.example.demo", observationRegistry)
            .observe {
                val order =
                    orderService.placeOrder(
                        PlaceOrderCommand(
                            sku = "spring-modulith-book",
                            amountCents = 4990,
                        ),
                    )

                logger.info("[Demo] Placed order {}", order.id)
            }

        logger.info("=== Demo Complete ===")
    }
}
