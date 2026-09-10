package io.namastack.demo

import io.namastack.outbox.instrumentation.OutboxInstrumentation
import io.namastack.outbox.instrumentation.OutboxProcessInvocation
import io.namastack.outbox.instrumentation.OutboxScheduleInvocation
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class CustomLoggingInstrumentation : OutboxInstrumentation {
    private val logger = LoggerFactory.getLogger(CustomLoggingInstrumentation::class.java)

    override fun schedule(
        invocation: OutboxScheduleInvocation,
        action: () -> Unit
    ) {
        logger.info("CustomLoggingInstrumentation: Scheduling outbox record with key: ${invocation.recordKey}")
        action()
    }

    override fun process(
        invocation: OutboxProcessInvocation,
        action: () -> Unit
    ) {
        logger.info("CustomLoggingInstrumentation: Processing outbox record with id: ${invocation.record.id}")
        action()
    }
}
