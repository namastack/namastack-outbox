package io.namastack.outbox

import io.namastack.outbox.OutboxRecordStatus.NEW
import java.time.OffsetDateTime
import java.util.UUID

object OutboxRecordTestFactory {
    fun outboxRecord(
        id: String = UUID.randomUUID().toString(),
        recordKey: String = UUID.randomUUID().toString(),
        recordType: String = "CreatedEvent",
        payload: String = "payload",
        partition: Int = 1,
        createdAt: OffsetDateTime = OffsetDateTime.now(),
        status: OutboxRecordStatus = NEW,
        completedAt: OffsetDateTime? = OffsetDateTime.now(),
        failureCount: Int = 0,
        nextRetryAt: OffsetDateTime = OffsetDateTime.now(),
    ): OutboxRecord =
        OutboxRecord.restore(
            id = id,
            recordKey = recordKey,
            recordType = recordType,
            payload = payload,
            partition = partition,
            createdAt = createdAt,
            status = status,
            completedAt = completedAt,
            nextRetryAt = nextRetryAt,
            failureCount = failureCount,
        )
}
