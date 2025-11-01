package io.namastack.demo

import io.namastack.demo.customer.CustomerService
import io.namastack.outbox.EnableOutbox
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Namastack Outbox for Spring Boot Demo Application
 *
 * This application demonstrates the declarative @OutboxEvent approach for implementing
 * the transactional outbox pattern with Spring Boot and Spring Data JPA.
 *
 * How It Works:
 * 1. Events are marked with @OutboxEvent annotation
 * 2. Aggregates use @DomainEvents to collect events during state changes
 * 3. repository.save() triggers @DomainEvents publication
 * 4. OutboxEventMulticaster intercepts @OutboxEvent annotated events
 * 5. Events are automatically serialized and persisted to outbox
 * 6. DemoRecordProcessor polls and processes events asynchronously
 *
 * Key Components:
 * - Customer: Aggregate root with domain events
 * - CustomerService: Clean service without manual outbox code
 * - OutboxEventListener: Demonstrates optional event listener
 * - DemoRecordProcessor: Processes outbox records asynchronously
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
        logger.info("")
        logger.info("╔════════════════════════════════════════════════════════════╗")
        logger.info("║  Namastack Outbox for Spring Boot - Demo Application v0.3.0 ║")
        logger.info("║  Demonstrating @OutboxEvent + @DomainEvents Pattern         ║")
        logger.info("╚════════════════════════════════════════════════════════════╝")
        logger.info("")

        Thread.sleep(500)

        demonstrateCustomerLifecycle()

        logger.info("")
        logger.info("✨ Demo flow completed!")
        logger.info("📊 Events are being processed asynchronously by the Outbox Processor")
        logger.info("💡 Press Ctrl+C to stop the application")
        logger.info("")
    }

    private fun demonstrateCustomerLifecycle() {
        logger.info("─────────────────────────────────────────────────────────────")
        logger.info("Step 1: Creating Customers")
        logger.info("─────────────────────────────────────────────────────────────")

        val customer1 = customerService.register("John", "Smith")
        val customer2 = customerService.register("Alice", "Thompson")
        val customer3 = customerService.register("Bruno", "Bertolli")

        logger.info("✓ 3 customers registered")
        logger.info("")

        Thread.sleep(1000)

        logger.info("─────────────────────────────────────────────────────────────")
        logger.info("Step 2: Activating Customers")
        logger.info("─────────────────────────────────────────────────────────────")

        customerService.activate(customer1.id)
        customerService.activate(customer2.id)
        customerService.activate(customer3.id)

        logger.info("✓ 3 customers activated")
        logger.info("")

        Thread.sleep(1000)

        logger.info("─────────────────────────────────────────────────────────────")
        logger.info("Step 3: Deactivating Customers")
        logger.info("─────────────────────────────────────────────────────────────")

        customerService.deactivate(customer1.id)
        customerService.deactivate(customer2.id)
        customerService.deactivate(customer3.id)

        logger.info("✓ 3 customers deactivated")
        logger.info("")

        logger.info("─────────────────────────────────────────────────────────────")
        logger.info("Events Published to Outbox!")
        logger.info("─────────────────────────────────────────────────────────────")
        logger.info("Waiting for asynchronous processing...")
        logger.info("")
    }
}
