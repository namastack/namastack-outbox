package io.namastack.outbox

/**
 * Mapper utility for converting between OutboxRecord domain objects and MongoOutboxRecordEntity entities.
 *
 * @author Stellar Hold
 * @since 1.5.0
 */
class MongoOutboxRecordEntityMapper(
    private val serializer: OutboxPayloadSerializer,
) {
    /**
     * Maps an [OutboxRecord] domain object to a [MongoOutboxRecordEntity] for MongoDB persistence.
     *
     * @param record the domain object to map
     * @return the corresponding MongoDB entity
     * @throws IllegalArgumentException if the record payload is null
     */
    fun map(record: OutboxRecord<*>): MongoOutboxRecordEntity {
        val payload = record.payload ?: throw IllegalArgumentException("record payload cannot be null")

        val serializedPayload = serializer.serialize(payload)
        val recordType = payload.javaClass.name
        val serializedContext = record.context.takeIf { it.isNotEmpty() }?.let { serializer.serialize(it) }

        return MongoOutboxRecordEntity(
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
     * Maps a [MongoOutboxRecordEntity] from MongoDB to an [OutboxRecord] domain object.
     *
     * @param entity the MongoDB entity to map
     * @return the corresponding domain object
     * @throws OutboxPayloadTypeNotFoundException if the record's payload type is unavailable
     */
    fun map(entity: MongoOutboxRecordEntity): OutboxRecord<*> {
        val clazz = resolveClass(entity)
        val payload = serializer.deserialize(entity.payload, clazz)

        @Suppress("UNCHECKED_CAST")
        val context =
            entity.context?.let {
                serializer.deserialize(it, Map::class.java as Class<Map<String, String>>)
            } ?: emptyMap()

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

    /**
     * Resolves a class by its fully qualified name using the current thread's context class loader.
     *
     * @param entity entity containing the payload type and record metadata
     * @return the resolved class
     * @throws OutboxPayloadTypeNotFoundException if the record's payload type is unavailable
     */
    private fun resolveClass(entity: MongoOutboxRecordEntity): Class<*> =
        try {
            Thread.currentThread().contextClassLoader.loadClass(entity.recordType)
        } catch (ex: ClassNotFoundException) {
            throw OutboxPayloadTypeNotFoundException(
                recordId = entity.id,
                recordKey = entity.recordKey,
                payloadType = entity.recordType,
                handlerId = entity.handlerId,
                cause = ex,
            )
        }
}
