package io.namastack.performance

import io.namastack.outbox.handler.OutboxHandler
import io.namastack.outbox.handler.OutboxRecordMetadata
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class RecordHandler : OutboxHandler {
    private val logger = LoggerFactory.getLogger(RecordHandler::class.java)

    override fun handle(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ) {
        logger.info("📝 Processing record for key: ${metadata.key}")
    }
}
