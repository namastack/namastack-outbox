package io.namastack.demo

import io.namastack.demo.customer.CustomerService
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class DemoController(
    val service: CustomerService
) {
    private val log = LoggerFactory.getLogger(DemoController::class.java)

    @PostMapping("/start-demo")
    fun registerCustomer() {
        log.info("[Controller] Register: John Wayne")
        service.register(firstname = "John", lastname = "Wayne", email = "john.wayne@example.com")
    }

    @DeleteMapping("/customer/{id}")
    fun removeCustomer(@PathVariable id: UUID) {
        log.info("[Controller] Remove user {}", id)
        service.remove(id)
    }
}
