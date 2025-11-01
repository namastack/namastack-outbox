package io.namastack.demo

import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordProcessor
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class DemoRecordProcessor : OutboxRecordProcessor {
    private val logger = LoggerFactory.getLogger(DemoRecordProcessor::class.java)

    override fun process(record: OutboxRecord) {
        logger.info("🔄 [Outbox Processor] Processing event: ${record.eventType} | Aggregate: ${record.aggregateId}")

        when (record.eventType) {
            "CustomerRegisteredEvent" -> handleCustomerRegistered()
            "CustomerActivatedEvent" -> handleCustomerActivated()
            "CustomerDeactivatedEvent" -> handleCustomerDeactivated()
            else -> logger.warn("⚠️ Unknown event type: ${record.eventType}")
        }

        logger.info("✅ [Outbox Processor] Event processed: ${record.eventType}")
    }

    private fun handleCustomerRegistered() {
        logger.info("📝 Handling CustomerRegistered")
        simulateExternalServiceCall("Email Service", "Send welcome email")
        simulateExternalServiceCall("CRM System", "Create customer profile")
    }

    private fun handleCustomerActivated() {
        logger.info("🎉 Handling CustomerActivated")
        simulateExternalServiceCall("Notification Service", "Send activation confirmation")
        simulateExternalServiceCall("Analytics Service", "Track activation")
    }

    private fun handleCustomerDeactivated() {
        logger.info("😔 Handling CustomerDeactivated")
        simulateExternalServiceCall("Cleanup Service", "Cleanup account data")
        simulateExternalServiceCall("Audit Service", "Log deactivation event")
    }

    private fun simulateExternalServiceCall(
        serviceName: String,
        operation: String,
    ) {
        logger.debug("📡 → $serviceName: $operation")
        Thread.sleep((50..100).random().toLong())

        if (Math.random() < 0.05) {
            logger.warn("❌ $serviceName failed (will retry)")
            throw RuntimeException("$serviceName temporarily unavailable")
        }

        logger.debug("✓ $serviceName completed")
    }
}
