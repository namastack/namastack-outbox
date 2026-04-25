package io.namastack.outbox

import io.namastack.outbox.partition.PartitionAssignment

/**
 * Mapper for converting between PartitionAssignment domain objects and JdbcOutboxPartitionAssignmentEntity entities.
 *
 * @author Roland Beisel
 * @since 1.0.0
 */
internal object JdbcOutboxPartitionAssignmentEntityMapper {
    /**
     * Converts a domain PartitionAssignment to an entity.
     */
    fun map(partition: PartitionAssignment): JdbcOutboxPartitionAssignmentEntity =
        JdbcOutboxPartitionAssignmentEntity(
            partitionNumber = partition.partitionNumber,
            instanceId = partition.instanceId,
            version = partition.version,
            updatedAt = partition.updatedAt,
            leaseExpiresAt = partition.leaseExpiresAt,
            draining = partition.draining,
        )

    /**
     * Converts an entity to a domain PartitionAssignment.
     */
    fun map(entity: JdbcOutboxPartitionAssignmentEntity): PartitionAssignment =
        PartitionAssignment(
            partitionNumber = entity.partitionNumber,
            instanceId = entity.instanceId,
            version = entity.version,
            updatedAt = entity.updatedAt,
            leaseExpiresAt = entity.leaseExpiresAt,
            draining = entity.draining,
        )
}
