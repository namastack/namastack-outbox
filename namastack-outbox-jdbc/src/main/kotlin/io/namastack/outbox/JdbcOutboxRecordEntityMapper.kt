package io.namastack.outbox

import io.namastack.outbox.event.OutboxRecordTypeResolver

/**
 * Mapper utility for converting between OutboxRecord domain objects and JdbcOutboxRecordEntity entities.
 *
 * Provides bidirectional mapping functionality to facilitate the translation between
 * the domain model and persistence layer representations.
 *
 * @author Roland Beisel
 * @since 1.0.0
 */
internal class JdbcOutboxRecordEntityMapper(
    private val serializer: OutboxPayloadSerializer,
    private val recordTypeResolver: OutboxRecordTypeResolver,
) {
    /**
     * Maps an OutboxRecord domain object to an JdbcOutboxRecordEntity entity.
     *
     * @param record The domain object to convert
     * @return Corresponding entity
     */
    fun map(record: OutboxRecord<*>): JdbcOutboxRecordEntity {
        val payload = record.payload ?: throw IllegalArgumentException("record payload cannot be null")

        val serializedPayload = serializer.serialize(payload)
        val recordType = recordTypeResolver.toRecordType(payload)
        val serializedContext =
            record.context
                .takeIf { it.isNotEmpty() }
                ?.let { serializer.serialize(it) }

        return JdbcOutboxRecordEntity(
            id = record.id,
            status = record.status,
            recordKey = record.key,
            recordType = recordType,
            payload = serializedPayload,
            context = serializedContext,
            partitionNo = record.partition,
            createdAt = record.createdAt,
            completedAt = record.completedAt,
            failureCount = record.failureCount,
            failureReason = record.failureReason,
            nextRetryAt = record.nextRetryAt,
            handlerId = record.handlerId,
        )
    }

    /**
     * Maps an JdbcOutboxRecordEntity entity to an OutboxRecord domain object.
     *
     * @param entity The entity to convert
     * @return Corresponding domain object
     */
    fun map(entity: JdbcOutboxRecordEntity): OutboxRecord<*> {
        val clazz = recordTypeResolver.resolveClass(entity.recordType)
        val payload = serializer.deserialize(entity.payload, clazz)

        @Suppress("UNCHECKED_CAST")
        val context =
            entity.context
                ?.let { serializer.deserialize(it, Map::class.java as Class<Map<String, String>>) }
                ?: emptyMap()

        return OutboxRecord.restore(
            id = entity.id,
            recordKey = entity.recordKey,
            payload = payload,
            context = context,
            partition = entity.partitionNo,
            createdAt = entity.createdAt,
            status = entity.status,
            completedAt = entity.completedAt,
            failureCount = entity.failureCount,
            failureReason = entity.failureReason,
            nextRetryAt = entity.nextRetryAt,
            handlerId = entity.handlerId,
            failureException = null,
        )
    }

}
