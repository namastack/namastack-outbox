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

    override fun claimPartition(
        partitionNumber: Int,
        instanceId: String,
    ): Unit =
        transactionTemplate.executeNonNull {
            val entity = entityManager.find(OutboxPartitionAssignmentEntity::class.java, partitionNumber)

            if (entity != null && entity.instanceId == null) {
                entity.reassignTo(newInstanceId = instanceId, timestamp = OffsetDateTime.now(clock))
                entityManager.merge(entity)
            } else if (entity == null) {
                // Partition doesn't exist - create it
                val newPartition =
                    PartitionAssignment.create(
                        partitionNumber = partitionNumber,
                        instanceId = instanceId,
                        clock = clock,
                    )
                val newEntity = OutboxPartitionAssignmentEntityMapper.toEntity(newPartition)
                entityManager.persist(newEntity)
            }
            // If entity.instanceId != null, it's already claimed - do nothing
        }

    override fun claimStalePartition(
        partitionNumber: Int,
        staleInstanceId: String,
        newInstanceId: String,
    ) = transactionTemplate.executeNonNull {
        val now = OffsetDateTime.now(clock)
        val entity = entityManager.find(OutboxPartitionAssignmentEntity::class.java, partitionNumber)

        if (entity == null || entity.instanceId != staleInstanceId) {
            return@executeNonNull
        }

        entity.reassignTo(newInstanceId, now)
        entityManager.merge(entity)
    }

    override fun claimStalePartitions(
        partitionIds: Set<Int>,
        staleInstanceIds: Set<String>?,
        newInstanceId: String,
    ) = transactionTemplate.executeNonNull {
        val now = OffsetDateTime.now(clock)

        // Load all partitions in one go
        val entities =
            partitionIds.map { partitionNumber ->
                entityManager.find(OutboxPartitionAssignmentEntity::class.java, partitionNumber)
            }

        // Verify all partitions exist and are either owned by staleInstanceId or released (null)
        entities.forEach { entity ->
            if (entity == null ||
                (staleInstanceIds?.contains(entity.instanceId) != true && entity.instanceId != null)
            ) {
                // Partition doesn't exist OR belongs to a different active instance
                // Exception will be thrown, transaction rolls back (all-or-nothing)
                throw IllegalStateException(
                    "Partition cannot be claimed: either missing or owned by different instance",
                )
            }
        }

        // All partitions verified - claim them all
        entities.forEach { entity ->
            entity?.reassignTo(newInstanceId, now)
            entity?.let { entityManager.merge(it) }
        }
    }

    override fun claimAllPartitions(instanceId: String) =
        transactionTemplate.executeNonNull {
            for (partitionNumber in 0..<TOTAL_PARTITIONS) {
                val newPartition =
                    PartitionAssignment.create(
                        partitionNumber = partitionNumber,
                        instanceId = instanceId,
                        clock = clock,
                    )
                val entity = OutboxPartitionAssignmentEntityMapper.toEntity(newPartition)
                entityManager.persist(entity)
            }
        }

    override fun releasePartition(
        partitionNumber: Int,
        currentInstanceId: String,
    ) = transactionTemplate.executeNonNull {
        val entity = entityManager.find(OutboxPartitionAssignmentEntity::class.java, partitionNumber)

        if (entity != null && entity.instanceId == currentInstanceId) {
            entity.instanceId = null
            entity.updatedAt = OffsetDateTime.now(clock)
            entityManager.merge(entity)
        }
    }
}
