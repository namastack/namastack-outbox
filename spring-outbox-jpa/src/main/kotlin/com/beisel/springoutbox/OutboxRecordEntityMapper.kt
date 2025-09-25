package com.beisel.springoutbox

object OutboxRecordEntityMapper {
    fun map(record: OutboxRecord): OutboxRecordEntity =
        OutboxRecordEntity(
            id = record.id,
            status = record.status,
            aggregateId = record.aggregateId,
            eventType = record.eventType,
            payload = record.payload,
            createdAt = record.createdAt,
            completedAt = record.completedAt,
            retryCount = record.retryCount,
            nextRetryAt = record.nextRetryAt,
        )

    fun map(entity: OutboxRecordEntity): OutboxRecord =
        OutboxRecord
            .Builder()
            .id(entity.id)
            .status(entity.status)
            .aggregateId(entity.aggregateId)
            .eventType(entity.eventType)
            .payload(entity.payload)
            .createdAt(entity.createdAt)
            .completedAt(entity.completedAt)
            .retryCount(entity.retryCount)
            .nextRetryAt(entity.nextRetryAt)
            .build()
}
