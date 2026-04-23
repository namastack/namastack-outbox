package io.namastack.outbox

import io.namastack.outbox.instance.OutboxInstance

/**
 * Mapper between OutboxInstance domain objects and MongoOutboxInstanceEntity entities.
 *
 * @author Stellar Hold
 * @since 1.5.0
 */
internal object MongoOutboxInstanceEntityMapper {
    /**
     * Maps an [OutboxInstance] domain object to a [MongoOutboxInstanceEntity] for MongoDB persistence.
     *
     * @param instance the domain object to map
     * @return the corresponding MongoDB entity
     */
    fun map(instance: OutboxInstance): MongoOutboxInstanceEntity =
        MongoOutboxInstanceEntity(
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
     * Maps a [MongoOutboxInstanceEntity] from MongoDB to an [OutboxInstance] domain object.
     *
     * @param entity the MongoDB entity to map
     * @return the corresponding domain object
     */
    fun map(entity: MongoOutboxInstanceEntity): OutboxInstance =
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
