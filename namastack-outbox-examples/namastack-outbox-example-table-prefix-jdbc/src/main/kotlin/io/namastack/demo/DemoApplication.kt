package io.namastack.demo

import io.namastack.demo.customer.CustomerService
import io.namastack.outbox.annotation.EnableOutbox
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.scheduling.annotation.EnableScheduling

@EnableOutbox
@EnableScheduling
@SpringBootApplication
class DemoApplication(
    private val service: CustomerService,
) : CommandLineRunner {
    private val logger = LoggerFactory.getLogger(DemoApplication::class.java)

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            SpringApplication.run(DemoApplication::class.java, *args)
        }
    }

    override fun run(vararg args: String) {
        logger.info("=== Namastack Outbox JDBC Demo ===")
        logger.info("Register: John Wayne")
        val customer1 = service.register(firstname = "John", lastname = "Wayne", email = "john.wayne@example.com")

        logger.info("Register: Macy Grey")
        val customer2 = service.register(firstname = "Macy", lastname = "Grey", email = "macy.grey@example.com")

        logger.info("Waiting for processing...")
        Thread.sleep(2000)

        logger.info("Remove: {}", customer1.id)
        service.remove(customer1.id)

        logger.info("Remove: {}", customer2.id)
        service.remove(customer2.id)

        logger.info("=== Demo Complete ===")
    }
}
