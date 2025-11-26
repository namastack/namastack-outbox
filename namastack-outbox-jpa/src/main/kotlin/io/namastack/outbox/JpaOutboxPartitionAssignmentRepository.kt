package io.namastack.outbox

import io.namastack.outbox.OutboxPartitionAssignmentEntityMapper.toEntity
import io.namastack.outbox.partition.PartitionAssignment
import io.namastack.outbox.partition.PartitionAssignmentRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType.PESSIMISTIC_WRITE
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
     * @param partitionAssignments Set of partition assignments to save
     * @throws Exception if version mismatch detected (concurrent modification)
     */
    override fun saveAll(partitionAssignments: Set<PartitionAssignment>) =
        transactionTemplate.executeNonNull {
            partitionAssignments.forEach { partitionAssignment ->
                val entity = toEntity(partitionAssignment)
                entityManager.merge(entity)
            }
        }

    /**
     * Inserts partition assignments only if the assignment table is empty.
     *
     * This method is designed to be called during application startup by multiple instances
     * concurrently. It ensures that only ONE instance will initialize the partition assignments,
     * preventing duplicate initialization attempts.
     *
     * The method uses a two-phase coordination protocol:
     * 1. Acquires a pessimistic write lock on the distributed lock entity to serialize access
     * 2. Checks if any partition assignments already exist
     * 3. If table is empty, inserts all assignments in a single batch
     *
     * This approach guarantees:
     * - Only one instance can execute the insert block at a time (due to pessimistic lock)
     * - Once assignments are created by one instance, all other instances will see them
     *   and skip their initialization attempts
     * - No duplicate data is created despite concurrent startup
     *
     * @param partitionAssignments Set of partition assignments to insert if table is empty
     * @return true if this instance initialized the assignments, false if another instance already did
     */
    override fun insertIfAllAbsent(partitionAssignments: Set<PartitionAssignment>): Boolean =
        transactionTemplate.executeNonNull {
            entityManager.find(OutboxPartitionLockEntity::class.java, 1, PESSIMISTIC_WRITE)

            val existing = entityManager.find(OutboxPartitionAssignmentEntity::class.java, 1)

            if (existing == null) {
                partitionAssignments.forEach { partitionAssignment ->
                    val entity = toEntity(partitionAssignment)
                    entityManager.persist(entity)
                }
                return@executeNonNull true
            }
            false
        }
}
