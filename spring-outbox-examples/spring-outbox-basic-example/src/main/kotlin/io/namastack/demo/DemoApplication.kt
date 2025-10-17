package io.namastack.demo

import io.namastack.demo.customer.CustomerService
import io.namastack.springoutbox.EnableOutbox
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Spring Outbox Demo Application
 *
 * This application demonstrates the usage of the Spring Outbox library for implementing
 * the transactional outbox pattern in a Spring Boot application.
 *
 * Key Features Demonstrated:
 * - Reliable event publishing using the outbox pattern
 * - Event processing with custom processors
 * - Transactional safety guarantees
 * - Domain event ordering per aggregate
 *
 * @author Roland Beisel
 */
@EnableOutbox
@EnableScheduling
@SpringBootApplication
class DemoApplication(
    private val customerService: CustomerService,
) : CommandLineRunner {
    private val logger = LoggerFactory.getLogger(DemoApplication::class.java)

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            SpringApplication.run(DemoApplication::class.java, *args)
        }
    }

    override fun run(vararg args: String?) {
        logger.info("🚀 Starting Spring Outbox Demo...")
        logger.info("📋 This demo will showcase the transactional outbox pattern with customer lifecycle events")

        // Wait a moment for the application to fully initialize
        Thread.sleep(1000)

        demonstrateCustomerLifecycle()

        logger.info("✨ Demo completed successfully!")
        logger.info("💡 Check the logs above to see the outbox pattern in action")
        logger.info("🔗 You can also interact with the REST API at http://localhost:8080/api/customers")
    }

    private fun demonstrateCustomerLifecycle() {
        logger.info("📝 Creating customers...")
        val customer1 = customerService.register("John", "Smith")
        val customer2 = customerService.register("Alice", "Thompson")
        val customer3 = customerService.register("Bruno", "Bertolli")

        Thread.sleep(500)

        logger.info("✅ Activating customers...")
        customerService.activate(customer1.id)
        customerService.activate(customer2.id)
        customerService.activate(customer3.id)

        Thread.sleep(500)

        logger.info("❌ Deactivating customers...")
        customerService.deactivate(customer1.id)
        customerService.deactivate(customer2.id)
        customerService.deactivate(customer3.id)
    }
}
