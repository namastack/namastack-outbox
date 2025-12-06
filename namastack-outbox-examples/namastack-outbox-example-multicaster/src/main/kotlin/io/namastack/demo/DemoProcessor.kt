package io.namastack.demo

import io.namastack.outbox.annotation.OutboxHandler
import io.namastack.outbox.handler.OutboxRecordMetadata
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class DemoProcessor {
    private val logger = LoggerFactory.getLogger(DemoProcessor::class.java)

    @OutboxHandler
    fun handle(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ) {
        logger.info("Processing payload $payload for recordKey ${metadata.key}")
        simulateExternalServiceCall()
    }

    private fun simulateExternalServiceCall() {
        // Simulate network delay
        Thread.sleep((50..150).random().toLong())

        // Occasionally simulate failures for retry demonstration
        if (Math.random() < 0.3) { // 30% failure rate
            logger.warn("❌ Service temporarily unavailable - will retry")
            throw RuntimeException("Simulated failure in DemoProcessor")
        }

        logger.info("✅ Processing completed")
    }
}
