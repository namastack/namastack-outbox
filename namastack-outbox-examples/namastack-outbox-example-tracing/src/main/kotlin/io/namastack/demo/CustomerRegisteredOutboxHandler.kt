package io.namastack.demo

import io.micrometer.tracing.Tracer
import io.namastack.demo.customer.CustomerRegisteredEvent
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.handler.OutboxTypedHandler
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class CustomerRegisteredOutboxHandler(
    private val handlerSpanFactory: HandlerSpanFactory,
    private val tracer: Tracer,
) : OutboxTypedHandler<CustomerRegisteredEvent> {
    private val logger = LoggerFactory.getLogger(CustomerRegisteredOutboxHandler::class.java)

    override fun handle(
        payload: CustomerRegisteredEvent,
        metadata: OutboxRecordMetadata,
    ) {
        val span =
            handlerSpanFactory.create("send email", metadata)
                ?: throw IllegalStateException("Could not create span.")

        tracer.runWithSpan(span) {
            logger.info("[Handler] Send email to: {}", payload.email)
            ExternalMailService.send(payload.email)
        }
    }
}
