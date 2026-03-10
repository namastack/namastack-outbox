package io.namastack.outbox

import io.namastack.outbox.partition.PartitionAssignment

/**
 * Mapper between PartitionAssignment domain objects and MongoOutboxPartitionAssignmentEntity entities.
 *
 * @author Stellar Hold
 * @since 1.1.0
 */
internal object MongoOutboxPartitionAssignmentEntityMapper {
    fun map(assignment: PartitionAssignment): MongoOutboxPartitionAssignmentEntity =
        MongoOutboxPartitionAssignmentEntity(
            partitionNumber = assignment.partitionNumber,
            instanceId = assignment.instanceId,
            version = assignment.version ?: 0,
            updatedAt = assignment.updatedAt,
        )

    fun map(entity: MongoOutboxPartitionAssignmentEntity): PartitionAssignment =
        PartitionAssignment(
            partitionNumber = entity.partitionNumber,
            instanceId = entity.instanceId,
            version = entity.version,
            updatedAt = entity.updatedAt,
        )
}
