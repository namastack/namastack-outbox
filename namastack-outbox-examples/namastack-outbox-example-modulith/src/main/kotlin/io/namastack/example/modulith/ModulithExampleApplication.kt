package io.namastack.example.modulith

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import io.namastack.example.modulith.order.OrderService
import io.namastack.example.modulith.order.PlaceOrderCommand
import io.namastack.example.modulith.payment.PaymentRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.ConfigurableApplicationContext

@SpringBootApplication
class ModulithExampleApplication(
    private val orderService: OrderService,
    private val paymentRepository: PaymentRepository,
    private val observationRegistry: ObservationRegistry,
    private val context: ConfigurableApplicationContext,
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

        Thread.sleep(30000)

        paymentRepository.findAll().forEach {
            logger.info("[Demo] Payment {} for order {} is {}", it.id, it.orderId, it.status)
        }

        logger.info("=== Demo Complete ===")
        context.close()
    }
}
