package io.namastack.outbox

import io.namastack.outbox.serializer.OutboxPayloadSerializerRegistry

/**
 * Mapper utility for converting between OutboxRecord domain objects and OutboxRecordEntity JPA entities.
 *
 * Provides bidirectional mapping functionality to facilitate the translation between
 * the domain model and persistence layer representations.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
internal class OutboxRecordEntityMapper(
    private val registry: OutboxPayloadSerializerRegistry,
) {
    fun map(record: OutboxRecord<*>): OutboxRecordEntity {
        val payload = record.payload ?: throw IllegalArgumentException("record payload cannot be null")

        val serializedPayload = registry.forType(payload.javaClass).serialize(payload)
        val recordType = payload.javaClass.name
        val serializedContext =
            record.context
                .takeIf { it.isNotEmpty() }
                ?.let { registry.default.serialize(it) }

        return OutboxRecordEntity(
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

    fun map(entity: OutboxRecordEntity): OutboxRecord<*> {
        val clazz = resolveClass(entity.recordType)
        val payload = registry.forType(clazz).deserialize(entity.payload, clazz)

        @Suppress("UNCHECKED_CAST")
        val context =
            entity.context
                ?.let { registry.default.deserialize(it, Map::class.java as Class<Map<String, String>>) }
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

    private fun resolveClass(className: String): Class<*> =
        try {
            Thread.currentThread().contextClassLoader.loadClass(className)
        } catch (ex: ClassNotFoundException) {
            throw IllegalStateException("Cannot find class for record type $className", ex)
        }
}
