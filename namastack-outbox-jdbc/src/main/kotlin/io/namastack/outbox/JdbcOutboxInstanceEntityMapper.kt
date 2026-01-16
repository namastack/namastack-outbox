package io.namastack.outbox

import io.namastack.outbox.instance.OutboxInstance

/**
 * Mapper between OutboxInstance domain objects and JdbcOutboxInstanceEntity entities.
 *
 * @author Roland Beisel
 * @since 1.0.0
 */
internal object JdbcOutboxInstanceEntityMapper {
    /**
     * Maps from domain object to entity.
     *
     * @param instance The domain object to convert
     * @return Corresponding entity
     */
    fun map(instance: OutboxInstance): JdbcOutboxInstanceEntity =
        JdbcOutboxInstanceEntity(
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
     * Maps from entity to domain object.
     *
     * @param entity The entity to convert
     * @return Corresponding domain object
     */
    fun map(entity: JdbcOutboxInstanceEntity): OutboxInstance =
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
}
