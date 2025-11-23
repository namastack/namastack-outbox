package io.namastack.outbox

import io.namastack.outbox.partition.PartitionAssignment
import io.namastack.outbox.partition.PartitionAssignmentRepository
import io.namastack.outbox.partition.PartitionHasher.TOTAL_PARTITIONS
import jakarta.persistence.EntityManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.OffsetDateTime

/**
 * JPA repository implementation for managing partition assignments.
 *
 * Uses optimistic locking (version field) to detect and prevent concurrent
 * modifications when multiple instances try to claim the same partition.
 *
 * @param entityManager JPA entity manager for database operations
 * @param transactionTemplate Transaction template for programmatic transaction management
 * @param clock Clock for timestamp generation
 *
 * @author Roland Beisel
 * @since 0.4.0
 */
internal open class JpaOutboxPartitionAssignmentRepository(
    private val entityManager: EntityManager,
    private val transactionTemplate: TransactionTemplate,
    private val clock: Clock,
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
     * Claims stale or unassigned partitions for a new instance.
     *
     * Uses optimistic locking to prevent race conditions. Throws IllegalStateException
     * if a partition is missing or owned by a different instance.
     *
     * @param partitionIds Partition numbers to claim
     * @param staleInstanceIds Instance IDs that owned partitions (null allows any owner)
     * @param newInstanceId Instance ID claiming the partitions
     * @throws IllegalStateException if partition missing or owned by different instance
     */
    override fun claimStalePartitions(
        partitionIds: Set<Int>,
        staleInstanceIds: Set<String>?,
        newInstanceId: String,
    ) = transactionTemplate.executeNonNull {
        val now = OffsetDateTime.now(clock)

        val entities =
            partitionIds.map { partitionNumber ->
                entityManager.find(OutboxPartitionAssignmentEntity::class.java, partitionNumber)
            }

        entities.forEach { entity ->
            if (entity == null ||
                (staleInstanceIds?.contains(entity.instanceId) != true && entity.instanceId != null)
            ) {
                throw IllegalStateException(
                    "Partition cannot be claimed: either missing or owned by different instance",
                )
            }

            entity.reassignTo(newInstanceId, now)
            entity.let { entityManager.merge(it) }
        }
    }

    /**
     * Claims all partitions for an instance during bootstrap.
     *
     * Inserts all 256 partitions in a single transaction. Uses all-or-nothing semantics:
     * either all 256 partitions are successfully inserted, or the transaction is rolled back.
     *
     * When multiple instances bootstrap concurrently, only one will succeed. The other
     * instances will encounter SQL Error 23505 (Duplicate Key Constraint Violation) on the
     * partition_number PRIMARY KEY. The exception is thrown and must be handled by the caller.
     *
     * Affected databases: H2, MariaDB, MySQL, Oracle, PostgreSQL, SQL Server
     * Error codes: PostgreSQL=23505, MySQL/MariaDB=1062, SQL Server=2627, Oracle=ORA-00001
     *
     * @param instanceId Instance ID claiming all partitions
     * @throws Exception if duplicate key or other database error occurs (handled by caller)
     */
    override fun claimAllPartitions(instanceId: String): Unit =
        transactionTemplate.executeNonNull {
            val partitionAssignmentsAlreadyExist =
                entityManager
                    .createQuery(
                        "select count(p) from OutboxPartitionAssignmentEntity p",
                        Long::class.java,
                    ).singleResult > 0L

            if (partitionAssignmentsAlreadyExist) return@executeNonNull

            for (partitionNumber in 0 until TOTAL_PARTITIONS) {
                val partition = PartitionAssignment.create(partitionNumber, instanceId, clock)
                val entity = OutboxPartitionAssignmentEntityMapper.toEntity(partition)
                entityManager.persist(entity)
            }
        }

    /**
     * Releases partitions from an instance, making them available for rebalancing.
     *
     * Uses optimistic locking (version field) to detect concurrent modifications.
     * Sets instanceId to null for the released partitions. Does nothing if partition set is empty.
     *
     * @param partitionNumbers Partition numbers to release
     * @param currentInstanceId Instance ID currently owning the partitions
     * @throws Exception if version mismatch detected (concurrent modification)
     */
    override fun releasePartitions(
        partitionNumbers: Set<Int>,
        currentInstanceId: String,
    ) = transactionTemplate.executeNonNull {
        if (partitionNumbers.isEmpty()) return@executeNonNull

        val now = OffsetDateTime.now(clock)
        val query = """
            update OutboxPartitionAssignmentEntity p
            set p.instanceId = null, p.updatedAt = :ts, p.version = p.version + 1
            where p.instanceId = :cid and p.partitionNumber in :parts
        """
        val rowsUpdated =
            entityManager
                .createQuery(query)
                .setParameter("ts", now)
                .setParameter("cid", currentInstanceId)
                .setParameter("parts", partitionNumbers)
                .executeUpdate()

        if (rowsUpdated != partitionNumbers.size) {
            throw IllegalStateException(
                "Expected to release ${partitionNumbers.size} partitions, but $rowsUpdated were updated. " +
                    "Concurrent modification by another instance detected.",
            )
        }
    }
}
