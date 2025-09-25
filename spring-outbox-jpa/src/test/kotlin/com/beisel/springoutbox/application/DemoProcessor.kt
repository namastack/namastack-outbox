package com.beisel.springoutbox.application

import com.beisel.springoutbox.OutboxRecord
import com.beisel.springoutbox.OutboxRecordProcessor
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class DemoProcessor : OutboxRecordProcessor {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun process(record: OutboxRecord) {
        // if (Math.random() < 0.3) throw RuntimeException("Simulated broker failure")

        log.debug("✅ Published {} for {}", record.eventType, record.aggregateId)
    }
}
