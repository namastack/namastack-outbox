package io.namastack.demo

import io.namastack.demo.customer.CustomerService
import io.namastack.outbox.annotation.EnableOutbox
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@EnableOutbox
@RestController
@EnableScheduling
@SpringBootApplication
class DemoApplication(
    private val customerService: CustomerService,
) {
    private val logger = LoggerFactory.getLogger(DemoApplication::class.java)

    @RequestMapping("/")
    fun home(): String {
        logger.info("home() has been called")
        customerService.register("firstname", "lastname", "email")

        return "Hello, World!"
    }
}

fun main(args: Array<String>) {
    runApplication<DemoApplication>(*args)
}
