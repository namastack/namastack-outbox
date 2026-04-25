package io.namastack.outbox

import io.namastack.outbox.partition.PartitionAssignment

/**
 * Mapper for converting between OutboxPartitionAssignmentEntity and PartitionAssignment.
 *
 * @author Roland Beisel
 * @since 0.4.0
 */
internal object OutboxPartitionAssignmentEntityMapper {
    /**
     * Converts a PartitionAssignment to a JPA entity.
     */
    fun toEntity(partition: PartitionAssignment): OutboxPartitionAssignmentEntity =
        OutboxPartitionAssignmentEntity(
            partitionNumber = partition.partitionNumber,
            instanceId = partition.instanceId,
            updatedAt = partition.updatedAt,
            leaseExpiresAt = partition.leaseExpiresAt,
            draining = partition.draining,
            version = partition.version,
        )

    /**
     * Converts a JPA entity to a PartitionAssignment.
     */
    fun fromEntity(entity: OutboxPartitionAssignmentEntity): PartitionAssignment =
        PartitionAssignment(
            partitionNumber = entity.partitionNumber,
            instanceId = entity.instanceId,
            updatedAt = entity.updatedAt,
            leaseExpiresAt = entity.leaseExpiresAt,
            draining = entity.draining,
            version = entity.version,
        )

    /**
     * Converts a list of JPA entities to objects.
     */
    fun fromEntities(entities: List<OutboxPartitionAssignmentEntity>): Set<PartitionAssignment> =
        entities.map { fromEntity(it) }.toSet()
}
