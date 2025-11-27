package io.namastack.outbox

import io.namastack.outbox.OutboxPartitionAssignmentEntityMapper.toEntity
import io.namastack.outbox.partition.PartitionAssignment
import io.namastack.outbox.partition.PartitionAssignmentRepository
import jakarta.persistence.EntityManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * JPA repository implementation for managing partition assignments.
 *
 * Uses optimistic locking (version field) to detect and prevent concurrent
 * modifications when multiple instances try to claim the same partition.
 *
 * @param entityManager JPA entity manager for database operations
 * @param transactionTemplate Transaction template for programmatic transaction management
 *
 * @author Roland Beisel
 * @since 0.4.0
 */
internal open class JpaOutboxPartitionAssignmentRepository(
    private val entityManager: EntityManager,
    private val transactionTemplate: TransactionTemplate,
) : PartitionAssignmentRepository {
    /**
     * Retrieves all partition assignments ordered by partition number.
     *
     * @return Set of all partition assignments
     */
    override fun findAll(): Set<PartitionAssignment> {
        val query = """
            select p from OutboxPartitionAssignmentEntity p
            order by p.partitionNumber asc
        """

        val entities =
            entityManager
                .createQuery(query, OutboxPartitionAssignmentEntity::class.java)
                .resultList

        return OutboxPartitionAssignmentEntityMapper.fromEntities(entities)
    }

    /**
     * Retrieves partition assignments for a specific instance.
     *
     * @param instanceId The instance ID to query
     * @return Set of partitions assigned to the instance
     */
    override fun findByInstanceId(instanceId: String): Set<PartitionAssignment> {
        val query = """
            select p from OutboxPartitionAssignmentEntity p
            where p.instanceId = :instanceId
        """

        val entities =
            entityManager
                .createQuery(query, OutboxPartitionAssignmentEntity::class.java)
                .setParameter("instanceId", instanceId)
                .resultList

        return OutboxPartitionAssignmentEntityMapper.fromEntities(entities)
    }

    /**
     * Saves partition assignments using optimistic locking.
     *
     * Updates existing assignments or inserts new ones. Uses the version field
     * to detect concurrent modifications. If a partition was modified by another
     * instance, merge() will throw an exception on version mismatch.
     *
     * Note: This method can throw a DataIntegrityViolationException (e.g. due to unique constraint violation)
     * if there are conflicts when inserting or updating records. This is expected behavior and should be handled by the caller.
     *
     * @param partitionAssignments Set of partition assignments to save
     * @throws org.springframework.dao.DataIntegrityViolationException on insert/update conflicts (expected behavior)
     * @throws org.springframework.dao.OptimisticLockingFailureException if version mismatch detected (concurrent modification)
     */
    override fun saveAll(partitionAssignments: Set<PartitionAssignment>) =
        transactionTemplate.executeNonNull {
            partitionAssignments.forEach { partitionAssignment ->
                val entity = toEntity(partitionAssignment)
                entityManager.merge(entity)
            }
        }
}
