package io.namastack.demo

import io.namastack.outbox.instrumentation.OutboxHandlerInvocation
import io.namastack.outbox.instrumentation.OutboxInstrumentation
import io.namastack.outbox.instrumentation.OutboxRecordProcessingInvocation
import io.namastack.outbox.instrumentation.OutboxRecordProcessingOutcome
import io.namastack.outbox.instrumentation.OutboxScheduleInvocation
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CustomLoggingInstrumentation : OutboxInstrumentation {
    private val logger = LoggerFactory.getLogger(CustomLoggingInstrumentation::class.java)

    override fun schedule(
        invocation: OutboxScheduleInvocation,
        action: () -> Unit,
    ) = observe("schedule", invocation.channel, action)

    override fun processRecord(
        invocation: OutboxRecordProcessingInvocation,
        action: () -> OutboxRecordProcessingOutcome,
    ): OutboxRecordProcessingOutcome =
        observe("process", invocation.channel, action)

    override fun invokeHandler(
        invocation: OutboxHandlerInvocation,
        action: () -> Unit,
    ) = observe("handler:${invocation.handlerKind}", invocation.channel, action)

    private fun <T> observe(
        operation: String,
        channel: String,
        action: () -> T,
    ): T {
        logger.info("Outbox {} started for channel {}", operation, channel)
        try {
            return action()
        } catch (failure: Throwable) {
            logger.warn("Outbox {} failed for channel {}", operation, channel, failure)
            throw failure
        }
    }
}
