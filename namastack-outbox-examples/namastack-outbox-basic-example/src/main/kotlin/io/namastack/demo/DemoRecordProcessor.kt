package io.namastack.demo

import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordProcessor
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class DemoRecordProcessor : OutboxRecordProcessor {
    private val logger = LoggerFactory.getLogger(DemoRecordProcessor::class.java)

    override fun process(record: OutboxRecord) {
        when (record.eventType) {
            "CustomerRegisteredEvent" -> handleCustomerRegistered(record)
            "CustomerActivatedEvent" -> handleCustomerActivated(record)
            "CustomerDeactivatedEvent" -> handleCustomerDeactivated(record)
            else -> handleUnknownEvent(record)
        }
    }

    private fun handleCustomerRegistered(record: OutboxRecord) {
        logger.info("📝 Processing CustomerRegistered event for aggregate: ${record.aggregateId}")

        // Simulate email service call
        simulateExternalServiceCall("Email Service", "Welcome email")

        // Simulate CRM integration
        simulateExternalServiceCall("CRM System", "Customer profile creation")

        logger.info("✅ CustomerRegistered event processed successfully")
    }

    private fun handleCustomerActivated(record: OutboxRecord) {
        logger.info("🎉 Processing CustomerActivated event for aggregate: ${record.aggregateId}")

        // Simulate notification service
        simulateExternalServiceCall("Notification Service", "Activation confirmation")

        // Simulate analytics tracking
        simulateExternalServiceCall("Analytics Service", "Customer activation tracking")

        logger.info("✅ CustomerActivated event processed successfully")
    }

    private fun handleCustomerDeactivated(record: OutboxRecord) {
        logger.info("😔 Processing CustomerDeactivated event for aggregate: ${record.aggregateId}")

        // Simulate cleanup services
        simulateExternalServiceCall("Cleanup Service", "Account deactivation")

        // Simulate audit logging
        simulateExternalServiceCall("Audit Service", "Deactivation audit log")

        logger.info("✅ CustomerDeactivated event processed successfully")
    }

    private fun handleUnknownEvent(record: OutboxRecord) {
        logger.warn("⚠️ Unknown event type: ${record.eventType} for aggregate: ${record.aggregateId}")
    }

    private fun simulateExternalServiceCall(
        serviceName: String,
        operation: String,
    ) {
        logger.debug("📡 Calling $serviceName for: $operation")

        // Simulate network delay
        Thread.sleep((50..150).random().toLong())

        // Occasionally simulate failures for retry demonstration
        if (Math.random() < 0.1) { // 10% failure rate
            logger.warn("❌ $serviceName temporarily unavailable - will retry")
            throw RuntimeException("Simulated failure in $serviceName: $operation")
        }

        logger.debug("✅ $serviceName call completed: $operation")
    }
}
