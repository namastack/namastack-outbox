package io.namastack.performance

import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class OutboxRecordController(
    private val outboxRecordRepository: OutboxRecordRepository,
) {
    @PostMapping("/outbox/record/{aggregateId}")
    fun createOutboxRecord(
        @PathVariable("aggregateId") aggregateId: String,
    ) {
        outboxRecordRepository.save(
            OutboxRecord
                .Builder()
                .aggregateId(aggregateId)
                .payload("random".repeat(100))
                .eventType("OutboxRecordCreatedEvent")
                .build(),
        )
    }
}
