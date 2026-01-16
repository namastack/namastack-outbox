package io.namastack.outbox

import io.namastack.outbox.OutboxRecordStatus.NEW
import java.time.Instant
import java.util.UUID

object OutboxRecordTestFactory {
    fun outboxRecord(
        id: String = UUID.randomUUID().toString(),
        recordKey: String = "test-record-key",
        payload: CreatedEvent = CreatedEvent(),
        context: Map<String, String> = emptyMap(),
        partition: Int = 1,
        createdAt: Instant = Instant.now(),
        status: OutboxRecordStatus = NEW,
        completedAt: Instant? = Instant.now(),
        failureCount: Int = 0,
        failureReason: String? = null,
        nextRetryAt: Instant = Instant.now(),
        handlerId: String = @Suppress("ktlint:standard:max-line-length")
        $$"io.namastack.outbox.OutboxRecordTestFactory$CreatedEventHandler#handle(io.namastack.outbox.OutboxRecordTestFactory$CreatedEvent)",
        failureException: Throwable? = null,
    ): OutboxRecord<CreatedEvent> =
        OutboxRecord.restore(
            id = id,
            recordKey = recordKey,
            payload = payload,
            context = context,
            partition = partition,
            createdAt = createdAt,
            status = status,
            completedAt = completedAt,
            nextRetryAt = nextRetryAt,
            failureCount = failureCount,
            failureReason = failureReason,
            handlerId = handlerId,
            failureException = failureException,
        )

    data class CreatedEvent(
        val id: String = UUID.randomUUID().toString(),
    )
}
