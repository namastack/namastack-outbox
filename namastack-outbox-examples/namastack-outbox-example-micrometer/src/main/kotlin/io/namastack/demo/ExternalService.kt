package io.namastack.demo

import io.namastack.demo.customer.CustomerRegisteredEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
class ExternalService(
    val restClient: RestClient,
) {
    private val logger = LoggerFactory.getLogger(ExternalService::class.java)

    fun removeCustomer(payload: CustomerRegisteredEvent) {
        Thread.sleep((50..150).random().toLong())

        if (Math.random() < 0.4) { // 40% failure rate
            logger.warn("[External] Service failed")
            throw RuntimeException("Simulated failure in ExternalService")
        }

        logger.info("[External] Sending request to remove customer: {}", payload.id)
        restClient.delete()
            .uri("customer/${payload.id}")
            .retrieve()
            .toBodilessEntity()
            .let { response -> logger.info("[External] Response received: {}", response.statusCode) }
    }
}
