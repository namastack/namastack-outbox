package io.namastack.demo

import io.namastack.demo.customer.CustomerRegisteredEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

@Component
class DemoEventListener {
    @Async
    @EventListener
    fun on(event: CustomerRegisteredEvent): Unit =
        throw RuntimeException("This exception does not affect the outbox transaction and behaviour.")
}
