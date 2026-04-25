package io.namastack.outbox

import io.namastack.outbox.partition.PartitionAssignment

/**
 * Mapper between PartitionAssignment domain objects and MongoOutboxPartitionAssignmentEntity entities.
 *
 * @author Stellar Hold
 * @since 1.5.0
 */
internal object MongoOutboxPartitionAssignmentEntityMapper {
    /**
     * Maps a [PartitionAssignment] domain object to a [MongoOutboxPartitionAssignmentEntity] for MongoDB persistence.
     *
     * @param assignment the domain object to map
     * @return the corresponding MongoDB entity
     */
    fun map(assignment: PartitionAssignment): MongoOutboxPartitionAssignmentEntity =
        MongoOutboxPartitionAssignmentEntity(
            partitionNumber = assignment.partitionNumber,
            instanceId = assignment.instanceId,
            version = assignment.version,
            updatedAt = assignment.updatedAt,
            leaseExpiresAt = assignment.leaseExpiresAt,
            draining = assignment.draining,
        )

    /**
     * Maps a [MongoOutboxPartitionAssignmentEntity] from MongoDB to a [PartitionAssignment] domain object.
     *
     * @param entity the MongoDB entity to map
     * @return the corresponding domain object
     */
    fun map(entity: MongoOutboxPartitionAssignmentEntity): PartitionAssignment =
        PartitionAssignment(
            partitionNumber = entity.partitionNumber,
            instanceId = entity.instanceId,
            version = entity.version,
            updatedAt = entity.updatedAt,
            leaseExpiresAt = entity.leaseExpiresAt,
            draining = entity.draining,
        )
}
