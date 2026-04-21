package io.namastack.outbox

import io.namastack.outbox.partition.PartitionAssignment
import io.namastack.outbox.partition.PartitionAssignmentRepository
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.transaction.support.TransactionTemplate

/**
 * MongoDB implementation of the PartitionAssignmentRepository interface.
 *
 * Uses MongoDB multi-document transactions to ensure atomic partition
 * reassignment, preventing split-brain scenarios during rebalancing.
 *
 * @author Stellar Hold
 * @since 1.5.0
 */
internal open class MongoOutboxPartitionAssignmentRepository(
    private val mongoTemplate: MongoTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val collectionNameResolver: MongoCollectionNameResolver,
) : PartitionAssignmentRepository {
    /**
     * Finds all partition assignments ordered by partition number.
     *
     * @return set of all partition assignments
     */
    override fun findAll(): Set<PartitionAssignment> {
        val query = Query().with(Sort.by(Sort.Order.asc("partitionNumber")))

        return mongoTemplate
            .find(
                query,
                MongoOutboxPartitionAssignmentEntity::class.java,
                collectionNameResolver.outboxPartitionAssignments,
            ).map { MongoOutboxPartitionAssignmentEntityMapper.map(it) }
            .toSet()
    }

    /**
     * Finds partition assignments by instance ID.
     *
     * @param instanceId the instance ID to filter by
     * @return set of partition assignments for the given instance
     */
    override fun findByInstanceId(instanceId: String): Set<PartitionAssignment> {
        val query = Query(Criteria.where("instanceId").`is`(instanceId))

        return mongoTemplate
            .find(
                query,
                MongoOutboxPartitionAssignmentEntity::class.java,
                collectionNameResolver.outboxPartitionAssignments,
            ).map { MongoOutboxPartitionAssignmentEntityMapper.map(it) }
            .toSet()
    }

    /**
     * Saves all partition assignments atomically within a MongoDB transaction.
     *
     * This ensures all-or-nothing semantics: if any partition save fails
     * (e.g., due to optimistic lock version mismatch), ALL changes are
     * rolled back — preventing inconsistent partition ownership state.
     */
    override fun saveAll(partitionAssignments: Set<PartitionAssignment>) {
        if (partitionAssignments.isEmpty()) return

        transactionTemplate.executeWithoutResult {
            partitionAssignments.forEach { assignment ->
                val entity = MongoOutboxPartitionAssignmentEntityMapper.map(assignment)

                try {
                    mongoTemplate.save(entity, collectionNameResolver.outboxPartitionAssignments)
                } catch (e: OptimisticLockingFailureException) {
                    throw OptimisticLockingFailureException(
                        "Partition assignment with partition number ${assignment.partitionNumber} " +
                            "was updated by another instance (version mismatch)",
                        e,
                    )
                }
            }
        }
    }
}
