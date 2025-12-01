package io.namastack.outbox

import io.namastack.outbox.OutboxRecordStatus.NEW
import java.time.OffsetDateTime
import java.util.UUID

object OutboxRecordTestFactory {
    fun outboxRecord(
        id: String = UUID.randomUUID().toString(),
        aggregateId: String = UUID.randomUUID().toString(),
        eventType: String = "CreatedEvent",
        payload: String = "payload",
        partition: Int = 1,
        createdAt: OffsetDateTime = OffsetDateTime.now(),
        status: OutboxRecordStatus = NEW,
        completedAt: OffsetDateTime? = OffsetDateTime.now(),
        retryCount: Int = 0,
        nextRetryAt: OffsetDateTime = OffsetDateTime.now(),
    ): OutboxRecord =
        OutboxRecord.restore(
            id = id,
            recordKey = aggregateId,
            recordType = eventType,
            payload = payload,
            partition = partition,
            createdAt = createdAt,
            status = status,
            completedAt = completedAt,
            nextRetryAt = nextRetryAt,
            retryCount = retryCount,
            processorName = "test-processor",
        )
}
