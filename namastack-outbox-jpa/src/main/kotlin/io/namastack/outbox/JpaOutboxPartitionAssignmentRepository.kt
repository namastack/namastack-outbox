package io.namastack.outbox

import io.namastack.outbox.OutboxPartitionLockEntity.Companion.SENTINEL_ID
import io.namastack.outbox.partition.PartitionAssignment
import io.namastack.outbox.partition.PartitionAssignmentRepository
import io.namastack.outbox.partition.PartitionHasher.TOTAL_PARTITIONS
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType.PESSIMISTIC_WRITE
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
        }

        entities.forEach { entity ->
            entity?.reassignTo(newInstanceId, now)
            entity?.let { entityManager.merge(it) }
        }
    }

    override fun claimAllPartitions(instanceId: String) =
        transactionTemplate.executeNonNull {
            entityManager.find(
                OutboxPartitionLockEntity::class.java,
                SENTINEL_ID,
                PESSIMISTIC_WRITE,
            )

            val partitionAssignmentsAlreadyExist =
                entityManager
                    .createQuery(
                        "select count(p) from OutboxPartitionAssignmentEntity p",
                        Long::class.java,
                    ).singleResult > 0L

            if (partitionAssignmentsAlreadyExist) return@executeNonNull

            for (partitionNumber in 0 until TOTAL_PARTITIONS) {
                val newPartition = PartitionAssignment.create(partitionNumber, instanceId, clock)
                val entity = OutboxPartitionAssignmentEntityMapper.toEntity(newPartition)
                entityManager.persist(entity)
            }
        }

    override fun releasePartitions(
        partitionNumbers: Set<Int>,
        currentInstanceId: String,
    ) = transactionTemplate.executeNonNull {
        if (partitionNumbers.isEmpty()) return@executeNonNull
        val now = OffsetDateTime.now(clock)
        val query = """
            update OutboxPartitionAssignmentEntity p
            set p.instanceId = null, p.updatedAt = :ts
            where p.instanceId = :cid and p.partitionNumber in :parts
        """
        entityManager
            .createQuery(query)
            .setParameter("ts", now)
            .setParameter("cid", currentInstanceId)
            .setParameter("parts", partitionNumbers)
            .executeUpdate()
    }
}
