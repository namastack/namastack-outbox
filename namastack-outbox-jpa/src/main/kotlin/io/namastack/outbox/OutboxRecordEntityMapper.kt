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
class OutboxRecordEntityMapper(
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
        val serializedContext =
            record.context
                .takeIf { it.isNotEmpty() }
                ?.let { serializer.serialize(it) }

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

    /**
     * Maps an OutboxRecordEntity JPA entity to an OutboxRecord domain object.
     *
     * @param entity The JPA entity to convert
     * @return Corresponding domain object
     * @throws OutboxPayloadTypeNotFoundException if the record's payload type is unavailable
     * @throws OutboxRecordDeserializationException if the payload or context cannot be deserialized
     */
    fun map(entity: OutboxRecordEntity): OutboxRecord<*> {
        val context = deserializeContext(entity)
        val clazz = resolveClass(entity, context)
        val payload = deserializePayload(entity, clazz, context)

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
     * Resolves a class by name using the current thread's context ClassLoader.
     *
     * @param entity Entity containing the payload type and record metadata
     * @return The resolved Class object
     * @throws OutboxPayloadTypeNotFoundException if the record's payload type is unavailable
     */
    private fun resolveClass(
        entity: OutboxRecordEntity,
        context: Map<String, String>,
    ): Class<*> =
        try {
            Thread.currentThread().contextClassLoader.loadClass(entity.recordType)
        } catch (ex: ClassNotFoundException) {
            throw OutboxPayloadTypeNotFoundException(
                recordId = entity.id,
                recordKey = entity.recordKey,
                payloadType = entity.recordType,
                handlerId = entity.handlerId,
                context = context,
                cause = ex,
            )
        } catch (ex: LinkageError) {
            throw OutboxPayloadTypeNotFoundException(
                recordId = entity.id,
                recordKey = entity.recordKey,
                payloadType = entity.recordType,
                handlerId = entity.handlerId,
                context = context,
                cause = ex,
            )
        }

    @Suppress("UNCHECKED_CAST")
    private fun deserializeContext(entity: OutboxRecordEntity): Map<String, String> =
        try {
            entity.context
                ?.let { serializer.deserialize(it, Map::class.java as Class<Map<String, String>>) }
                ?: emptyMap()
        } catch (ex: Exception) {
            throw deserializationException(entity, OutboxRecordDeserializationException.Target.CONTEXT, null, ex)
        }

    private fun deserializePayload(
        entity: OutboxRecordEntity,
        payloadType: Class<*>,
        context: Map<String, String>,
    ): Any =
        try {
            serializer.deserialize(entity.payload, payloadType)
        } catch (ex: Exception) {
            throw deserializationException(entity, OutboxRecordDeserializationException.Target.PAYLOAD, context, ex)
        }

    private fun deserializationException(
        entity: OutboxRecordEntity,
        target: OutboxRecordDeserializationException.Target,
        context: Map<String, String>?,
        cause: Exception,
    ) = OutboxRecordDeserializationException(
        recordId = entity.id,
        recordKey = entity.recordKey,
        payloadType = entity.recordType,
        handlerId = entity.handlerId,
        target = target,
        context = context,
        cause = cause,
    )
}
