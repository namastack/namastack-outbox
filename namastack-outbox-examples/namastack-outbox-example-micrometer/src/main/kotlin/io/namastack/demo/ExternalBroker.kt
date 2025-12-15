package io.namastack.demo

import io.namastack.demo.customer.CustomerRemovedEvent
import org.slf4j.LoggerFactory

object ExternalBroker {
    private val logger = LoggerFactory.getLogger(ExternalBroker::class.java)

    fun publish(event: CustomerRemovedEvent) {
        Thread.sleep((50..150).random().toLong())

        if (Math.random() < 0.4) { // 40% failure rate
            logger.warn("[External] Broker publish failed for key {}", event.id)
            throw RuntimeException("Simulated failure in ExternalBroker")
        }

        logger.info("[External] Event published with key {}", event.id)
    }
}
