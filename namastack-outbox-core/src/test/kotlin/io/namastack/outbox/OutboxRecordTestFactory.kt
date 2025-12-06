package io.namastack.outbox

import io.namastack.outbox.OutboxRecordStatus.NEW
import java.time.OffsetDateTime
import java.util.UUID

object OutboxRecordTestFactory {
    fun outboxRecord(
        id: String = UUID.randomUUID().toString(),
        recordKey: String = "test-record-key",
        payload: CreatedEvent = CreatedEvent(),
        partition: Int = 1,
        createdAt: OffsetDateTime = OffsetDateTime.now(),
        status: OutboxRecordStatus = NEW,
        completedAt: OffsetDateTime? = OffsetDateTime.now(),
        failureCount: Int = 0,
        nextRetryAt: OffsetDateTime = OffsetDateTime.now(),
        handlerId: String = @Suppress("ktlint:standard:max-line-length")
        $$"io.namastack.outbox.OutboxRecordTestFactory$CreatedEventHandler#handle(io.namastack.outbox.OutboxRecordTestFactory$CreatedEvent)",
    ): OutboxRecord<CreatedEvent> =
        OutboxRecord.restore(
            id = id,
            recordKey = recordKey,
            payload = payload,
            partition = partition,
            createdAt = createdAt,
            status = status,
            completedAt = completedAt,
            nextRetryAt = nextRetryAt,
            failureCount = failureCount,
            handlerId = handlerId,
        )

    data class CreatedEvent(
        val id: String = UUID.randomUUID().toString(),
    )
}
