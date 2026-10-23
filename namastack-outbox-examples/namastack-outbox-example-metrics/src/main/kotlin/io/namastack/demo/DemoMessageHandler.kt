package io.namastack.demo

import io.namastack.outbox.handler.OutboxHandlerIdentity
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.handler.OutboxTypedHandler
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class DemoMessageHandler : OutboxTypedHandler<DemoMessage> {
    private val logger = LoggerFactory.getLogger(DemoMessageHandler::class.java)

    override fun getTypedHandlerIdentity() = OutboxHandlerIdentity("demo.deliver-message")

    override fun handle(payload: DemoMessage, metadata: OutboxRecordMetadata) {
        check(!payload.fail) { "Intentional failure for metrics demo: ${payload.id}" }
        logger.info("Delivered message {}", payload.id)
    }
}
