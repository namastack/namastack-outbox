package io.namastack.demo

import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordProcessor
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class DemoProcessor1 : OutboxRecordProcessor {
    private val logger = LoggerFactory.getLogger(DemoProcessor1::class.java)

    override fun process(record: OutboxRecord) {
        logger.info(
            "Processing record ${record.id} for aggregate ${record.recordKey} with processor ${this::class.simpleName!!}",
        )

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
