package io.namastack.outbox

import io.namastack.outbox.OutboxRecordStatus.NEW
import java.time.OffsetDateTime
import java.util.UUID

object OutboxRecordTestFactory {
    fun outboxRecord(
        id: String? = null,
        recordKey: String? = null,
        attributes: Map<String, String>? = null,
        partition: Int? = null,
        createdAt: OffsetDateTime? = null,
        status: OutboxRecordStatus? = null,
        completedAt: OffsetDateTime? = null,
        failureCount: Int? = null,
        nextRetryAt: OffsetDateTime? = null,
        handlerId: String? = null,
    ): OutboxRecord<CreatedEvent> =
        outboxRecord(
            payload = CreatedEvent(),
            id = id,
            recordKey = recordKey,
            attributes = attributes,
            partition = partition,
            createdAt = createdAt,
            status = status,
            completedAt = completedAt,
            nextRetryAt = nextRetryAt,
            failureCount = failureCount,
            handlerId = handlerId,
        )

    @Suppress("ktlint:standard:max-line-length")
    fun <T> outboxRecord(
        payload: T,
        id: String? = null,
        recordKey: String? = null,
        attributes: Map<String, String>? = null,
        partition: Int? = null,
        createdAt: OffsetDateTime? = null,
        status: OutboxRecordStatus? = null,
        completedAt: OffsetDateTime? = null,
        failureCount: Int? = null,
        nextRetryAt: OffsetDateTime? = null,
        handlerId: String? = null,
    ): OutboxRecord<T> =
        OutboxRecord.restore(
            id = id ?: UUID.randomUUID().toString(),
            recordKey = recordKey ?: "test-record-key",
            payload = payload,
            context = attributes ?: emptyMap(),
            partition = partition ?: 1,
            createdAt = createdAt ?: OffsetDateTime.now(),
            status = status ?: NEW,
            completedAt = completedAt ?: OffsetDateTime.now(),
            nextRetryAt = nextRetryAt ?: OffsetDateTime.now(),
            failureCount = failureCount ?: 0,
            handlerId =
                handlerId
                    ?: $$"io.namastack.outbox.OutboxRecordTestFactory$CreatedEventHandler#handle(io.namastack.outbox.OutboxRecordTestFactory$CreatedEvent)",
        )

    data class CreatedEvent(
        val id: String = UUID.randomUUID().toString(),
    )
}
