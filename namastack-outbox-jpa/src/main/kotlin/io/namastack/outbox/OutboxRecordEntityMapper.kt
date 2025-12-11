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
internal class OutboxRecordEntityMapper(
    private val serializer: OutboxPayloadSerializer,
) {
    /**
     * Maps an OutboxRecord domain object to an OutboxRecordEntity JPA entity.
     *
     * @param record The domain object to convert
     * @return Corresponding JPA entity
     */
    fun map(record: OutboxRecord<*>): OutboxRecordEntity {
        val payload = record.payload ?: throw IllegalArgumentException("record payload cannot be null")

        val serializedPayload = serializer.serialize(payload)
        val recordType = payload.javaClass.name

        return OutboxRecordEntity(
            id = record.id,
            status = record.status,
            recordKey = record.key,
            recordType = recordType,
            payload = serializedPayload,
            partitionNo = record.partition,
            createdAt = record.createdAt,
            completedAt = record.completedAt,
            failureCount = record.failureCount,
            nextRetryAt = record.nextRetryAt,
            handlerId = record.handlerId,
        )
    }

    /**
     * Maps an OutboxRecordEntity JPA entity to an OutboxRecord domain object.
     *
     * @param entity The JPA entity to convert
     * @return Corresponding domain object
     */
    fun map(entity: OutboxRecordEntity): OutboxRecord<*> {
        val clazz = resolveClass(entity.recordType)
        val payload = serializer.deserialize(entity.payload, clazz)

        return OutboxRecord.restore(
            id = entity.id,
            recordKey = entity.recordKey,
            payload = payload,
            partition = entity.partitionNo,
            createdAt = entity.createdAt,
            status = entity.status,
            completedAt = entity.completedAt,
            failureCount = entity.failureCount,
            nextRetryAt = entity.nextRetryAt,
            handlerId = entity.handlerId,
        )
    }

    /**
     * Resolves a class by name using the current thread's context ClassLoader.
     *
     * @param className The fully qualified class name
     * @return The resolved Class object
     * @throws IllegalStateException if the class cannot be found
     */
    private fun resolveClass(className: String): Class<*> =
        try {
            Thread.currentThread().contextClassLoader.loadClass(className)
        } catch (ex: ClassNotFoundException) {
            throw IllegalStateException("Cannot find class for record type $className", ex)
        }
}
