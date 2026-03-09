package io.namastack.demo

import io.micrometer.observation.annotation.Observed
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class ExternalBroker {
    private val logger = LoggerFactory.getLogger(ExternalBroker::class.java)

    @Observed(name = "external.broker.publish", contextualName = "publish event to broker")
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
