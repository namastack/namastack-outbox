package io.namastack.performance

import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@RestController
class OutboxRecordController(
    private val outboxRecordRepository: OutboxRecordRepository,
    private val jsonMapper: JsonMapper,
    private val clock: Clock,
) {
    @PostMapping("/outbox/record/{recordKey}")
    fun createOutboxRecord(
        @PathVariable("recordKey") recordKey: String,
    ): Mono<OutboxRecord> {
        val now = OffsetDateTime.now(clock)
        val partition = PartitionHasher.getPartitionForRecordKey(recordKey)

        return outboxRecordRepository.save(
            OutboxRecord(
                entityId = UUID.randomUUID().toString(),
                status = "NEW",
                recordKey = recordKey,
                recordType = "java.lang.String",
                payload = jsonMapper.writeValueAsString("payload".repeat(30)),
                partitionNo = partition,
                createdAt = now,
                completedAt = null,
                failureCount = 0,
                nextRetryAt = now,
                handlerId = @Suppress("ktlint:standard:max-line-length")
                "io.namastack.performance.RecordHandler#handle(java.lang.Object,io.namastack.outbox.handler.OutboxRecordMetadata)",
            ),
        )
    }
}
