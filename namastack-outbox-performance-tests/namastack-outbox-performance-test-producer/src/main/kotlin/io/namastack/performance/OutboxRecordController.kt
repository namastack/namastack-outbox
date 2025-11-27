package io.namastack.performance

import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@RestController
class OutboxRecordController(
    private val outboxRecordRepository: OutboxRecordRepository,
    private val clock: Clock,
) {
    @PostMapping("/outbox/record/{aggregateId}")
    fun createOutboxRecord(
        @PathVariable("aggregateId") aggregateId: String,
    ): Mono<OutboxRecord> {
        val now = OffsetDateTime.now(clock)
        val partition = PartitionHasher.getPartitionForAggregate(aggregateId)

        return outboxRecordRepository.save(
            OutboxRecord(
                entityId = UUID.randomUUID().toString(),
                status = "NEW",
                aggregateId = aggregateId,
                eventType = "eventType",
                payload = "payload".repeat(30),
                partitionNo = partition,
                createdAt = now,
                completedAt = null,
                retryCount = 0,
                nextRetryAt = now,
            ),
        )
    }
}
