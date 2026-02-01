package io.namastack.demo

import org.slf4j.LoggerFactory

object ExternalBroker {
    private val logger = LoggerFactory.getLogger(ExternalBroker::class.java)

    fun publish(
        event: Any,
        key: String,
    ) {
        Thread.sleep((50..150).random().toLong())

        if (Math.random() < 0.4) { // 40% failure rate
            logger.warn("[External] Broker publish failed for key {}", key)
            throw RuntimeException("Simulated failure in ExternalBroker")
        }

        logger.info("[External] Event published with key {}", key)
    }
}
