package io.namastack.demo

import io.micrometer.observation.annotation.Observed
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class ExternalMailService {
    private val logger = LoggerFactory.getLogger(ExternalMailService::class.java)

    @Observed(name = "external.mail.send", contextualName = "send email")
    fun send(email: String) {
        Thread.sleep((50..150).random().toLong())

        if (Math.random() < 0.4) { // 40% failure rate
            logger.warn("[External] Mail service failed for {}", email)
            throw RuntimeException("Simulated failure in ExternalMailService")
        }

        logger.info("[External] Mail sent to {}", email)
    }
}
