package io.namastack.demo

import io.namastack.demo.customer.CustomerService
import io.namastack.outbox.EnableOutbox
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.scheduling.annotation.EnableScheduling

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

    override fun run(vararg args: String) {
        logger.info("Starting Namastack Outbox Demo Application")
        customerService.registerNew(firstname = "John", lastname = "Wayne", email = "john.wayne@example.com")
        customerService.registerNew(firstname = "Macy", lastname = "Grey", email = "macy.grey@example.com")
        customerService.registerNew(firstname = "Lil", lastname = "Joe", email = "lil.joe@example.com")
    }
}
