package io.namastack.outbox

import io.namastack.outbox.partition.PartitionAssignment

/**
 * Mapper for converting between OutboxPartitionEntity and OutboxPartition.
 *
 * @author Roland Beisel
 * @since 0.4.0
 */
internal object OutboxPartitionAssignmentEntityMapper {
    /**
     * Converts a domain OutboxPartition to a JPA entity.
     */
    fun toEntity(partition: PartitionAssignment): OutboxPartitionAssignmentEntity =
        OutboxPartitionAssignmentEntity(
            partitionNumber = partition.partitionNumber,
            instanceId = partition.instanceId,
            assignedAt = partition.assignedAt,
            updatedAt = partition.assignedAt,
        )

    /**
     * Converts a JPA entity to a domain OutboxPartition.
     */
    fun fromEntity(entity: OutboxPartitionAssignmentEntity): PartitionAssignment =
        PartitionAssignment(
            partitionNumber = entity.partitionNumber,
            instanceId = entity.instanceId,
            assignedAt = entity.assignedAt,
        )

    /**
     * Converts a list of JPA entities to domain objects.
     */
    fun fromEntities(entities: List<OutboxPartitionAssignmentEntity>): Set<PartitionAssignment> =
        entities.map { fromEntity(it) }.toSet()
}
