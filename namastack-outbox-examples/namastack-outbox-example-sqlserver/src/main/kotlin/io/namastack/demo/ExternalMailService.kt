package io.namastack.demo

import org.slf4j.LoggerFactory

object ExternalMailService {
    private val logger = LoggerFactory.getLogger(ExternalMailService::class.java)

    fun send(email: String) {
        Thread.sleep((50..150).random().toLong())

        if (Math.random() < 0.4) { // 40% failure rate
            logger.warn("[External] Mail service failed for {}", email)
            throw RuntimeException("Simulated failure in ExternalMailService")
        }

        logger.info("[External] Mail sent to {}", email)
    }
}
