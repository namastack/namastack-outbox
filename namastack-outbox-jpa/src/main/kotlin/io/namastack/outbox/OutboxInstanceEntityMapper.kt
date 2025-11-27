package io.namastack.outbox

import io.namastack.outbox.instance.OutboxInstance

/**
 * Mapper between OutboxInstance domain objects and OutboxInstanceEntity JPA entities.
 *
 * @author Roland Beisel
 * @since 0.2.0
 */
internal object OutboxInstanceEntityMapper {
    /**
     * Maps from domain object to JPA entity.
     *
     * @param instance The domain object to convert
     * @return Corresponding JPA entity
     */
    fun toEntity(instance: OutboxInstance): OutboxInstanceEntity =
        OutboxInstanceEntity(
            instanceId = instance.instanceId,
            hostname = instance.hostname,
            port = instance.port,
            status = instance.status,
            startedAt = instance.startedAt,
            lastHeartbeat = instance.lastHeartbeat,
            createdAt = instance.createdAt,
            updatedAt = instance.updatedAt,
        )

    /**
     * Maps from JPA entity to domain object.
     *
     * @param entity The JPA entity to convert
     * @return Corresponding domain object
     */
    fun fromEntity(entity: OutboxInstanceEntity): OutboxInstance =
        OutboxInstance(
            instanceId = entity.instanceId,
            hostname = entity.hostname,
            port = entity.port,
            status = entity.status,
            startedAt = entity.startedAt,
            lastHeartbeat = entity.lastHeartbeat,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
        )

    /**
     * Maps a list of entities to domain objects.
     */
    fun fromEntities(entities: List<OutboxInstanceEntity>): List<OutboxInstance> = entities.map { fromEntity(it) }
}
