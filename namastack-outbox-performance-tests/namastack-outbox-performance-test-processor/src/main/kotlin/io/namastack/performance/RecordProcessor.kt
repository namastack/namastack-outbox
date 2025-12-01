package io.namastack.performance

import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordProcessor
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class RecordProcessor : OutboxRecordProcessor {
    private val logger = LoggerFactory.getLogger(RecordProcessor::class.java)

    override fun process(record: OutboxRecord) {
//        if (Math.random() < 0.6) { // 10% failure rate
//            throw RuntimeException("Simulated failure")
//        }
        logger.info("📝 Processing record ${record.id} for aggregate: ${record.recordKey}")
    }
}
