package io.namastack.outbox

/**
 * Mapper utility for converting between OutboxRecord domain objects and OutboxRecordEntity JPA entities.
 *
 * Provides bidirectional mapping functionality to facilitate the translation between
 * the domain model and persistence layer representations.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
internal object OutboxRecordEntityMapper {
    /**
     * Maps an OutboxRecord domain object to an OutboxRecordEntity JPA entity.
     *
     * @param record The domain object to convert
     * @return Corresponding JPA entity
     */
    fun map(record: OutboxRecord): OutboxRecordEntity =
        OutboxRecordEntity(
            id = record.id,
            status = record.status,
            aggregateId = record.aggregateId,
            eventType = record.eventType,
            payload = record.payload,
            partition = record.partition,
            createdAt = record.createdAt,
            completedAt = record.completedAt,
            retryCount = record.retryCount,
            nextRetryAt = record.nextRetryAt,
        )

    /**
     * Maps an OutboxRecordEntity JPA entity to an OutboxRecord domain object.
     *
     * @param entity The JPA entity to convert
     * @return Corresponding domain object
     */
    fun map(entity: OutboxRecordEntity): OutboxRecord =
        OutboxRecord.restore(
            id = entity.id,
            aggregateId = entity.aggregateId,
            eventType = entity.eventType,
            payload = entity.payload,
            partition = entity.partition,
            createdAt = entity.createdAt,
            status = entity.status,
            completedAt = entity.completedAt,
            retryCount = entity.retryCount,
            nextRetryAt = entity.nextRetryAt,
        )
}
