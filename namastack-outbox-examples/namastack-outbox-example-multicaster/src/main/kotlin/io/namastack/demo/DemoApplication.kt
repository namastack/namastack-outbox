package io.namastack.demo

import io.namastack.demo.customer.Customer
import io.namastack.demo.customer.CustomerRepository
import io.namastack.outbox.EnableOutbox
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling

@EnableAsync
@EnableOutbox
@EnableScheduling
@SpringBootApplication
class DemoApplication(
    private val customerRepository: CustomerRepository,
) : CommandLineRunner {
    private val logger = LoggerFactory.getLogger(DemoApplication::class.java)

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            SpringApplication.run(DemoApplication::class.java, *args)
        }
    }

    override fun run(vararg args: String?) {
        logger.info("Starting Namastack Outbox Demo Application")

        Thread {
            val customer1 = Customer.register(firstname = "John", lastname = "Wayne", email = "john.wayne@example.com")
            customerRepository.save(customer1)

            val customer2 = Customer.register(firstname = "Macy", lastname = "Grey", email = "macy.grey@example.com")
            customerRepository.save(customer2)

            val customer3 = Customer.register(firstname = "Lil", lastname = "Joe", email = "lil.joe@example.com")
            customerRepository.save(customer3)
        }.start()
    }
}
