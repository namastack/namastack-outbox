package io.namastack.outbox

import io.namastack.outbox.partition.PartitionAssignment
import io.namastack.outbox.partition.PartitionAssignmentRepository
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate

/**
 * JDBC repository implementation for managing partition assignments.
 *
 * Uses optimistic locking (version field) to detect and prevent concurrent
 * modifications when multiple instances try to claim the same partition.
 *
 * @param jdbcClient JDBC client for database operations
 * @param transactionTemplate Transaction template for programmatic transaction management
 *
 * @author Roland Beisel
 * @since 1.1.0
 */
internal open class JdbcOutboxPartitionAssignmentRepository(
    private val jdbcClient: JdbcClient,
    private val transactionTemplate: TransactionTemplate,
) : PartitionAssignmentRepository {
    /**
     * Query to select all partition assignments ordered by partition number.
     */
    private val findAllQuery = """
        SELECT * FROM outbox_partition
        ORDER BY partition_number
    """

    /**
     * Query to select partition assignments by instance ID.
     */
    private val findByInstanceIdQuery = """
        SELECT * FROM outbox_partition
        WHERE instance_id = ?
    """

    /**
     * Query to update partition assignment with optimistic locking.
     */
    private val updatePartitionQuery = """
        UPDATE outbox_partition
        SET instance_id = ?, version = ?, updated_at = ?
        WHERE partition_number = ? AND version = ?
    """

    /**
     * Query to check if partition exists.
     */
    private val partitionExistsQuery = """
        SELECT COUNT(*) FROM outbox_partition
        WHERE partition_number = ?
    """

    /**
     * Query to insert new partition assignment.
     */
    private val insertPartitionQuery = """
        INSERT INTO outbox_partition (partition_number, instance_id, version, updated_at)
        VALUES (?, ?, ?, ?)
    """

    /**
     * Finds all partition assignments ordered by partition number.
     */
    override fun findAll(): Set<PartitionAssignment> =
        jdbcClient
            .sql(findAllQuery)
            .query(JdbcOutboxPartitionAssignmentEntity::class.java)
            .list()
            .filterNotNull()
            .map { JdbcOutboxPartitionAssignmentEntityMapper.map(it) }
            .toSet()

    /**
     * Finds partition assignments by instance ID.
     */
    override fun findByInstanceId(instanceId: String): Set<PartitionAssignment> =
        jdbcClient
            .sql(findByInstanceIdQuery)
            .param(instanceId)
            .query(JdbcOutboxPartitionAssignmentEntity::class.java)
            .list()
            .filterNotNull()
            .map { JdbcOutboxPartitionAssignmentEntityMapper.map(it) }
            .toSet()

    /**
     * Saves all partition assignments with optimistic locking.
     * Throws OptimisticLockingFailureException if version mismatch detected.
     */
    override fun saveAll(partitionAssignments: Set<PartitionAssignment>) {
        transactionTemplate.execute {
            partitionAssignments.forEach { assignment ->
                val entity = JdbcOutboxPartitionAssignmentEntityMapper.map(assignment)
                savePartitionAssignment(entity, assignment.version)
            }
        }
    }

    /**
     * Saves a single partition assignment.
     * Tries update first, then insert if not exists, or throws exception if version mismatch.
     */
    private fun savePartitionAssignment(
        entity: JdbcOutboxPartitionAssignmentEntity,
        originalVersion: Long?,
    ) {
        val newVersion = (originalVersion ?: 0) + 1
        val updated = tryUpdate(entity, newVersion, originalVersion)

        if (updated == 0) {
            handleUpdateFailure(entity)
        }
    }

    /**
     * Attempts to update partition assignment with optimistic locking.
     * Returns number of rows updated.
     */
    private fun tryUpdate(
        entity: JdbcOutboxPartitionAssignmentEntity,
        newVersion: Long,
        originalVersion: Long?,
    ): Int =
        jdbcClient
            .sql(updatePartitionQuery.trimIndent())
            .param(entity.instanceId)
            .param(newVersion)
            .param(entity.updatedAt)
            .param(entity.partitionNumber)
            .param(originalVersion ?: 0)
            .update()

    /**
     * Handles case when update fails - either partition doesn't exist (insert) or version mismatch (exception).
     */
    private fun handleUpdateFailure(entity: JdbcOutboxPartitionAssignmentEntity) {
        if (partitionExists(entity.partitionNumber)) {
            throw OptimisticLockingFailureException(
                "Partition assignment with partition number ${entity.partitionNumber} " +
                    "was updated by another instance (version mismatch)",
            )
        } else {
            insertPartition(entity)
        }
    }

    /**
     * Checks if partition exists in database.
     */
    private fun partitionExists(partitionNumber: Int): Boolean =
        jdbcClient
            .sql(partitionExistsQuery)
            .param(partitionNumber)
            .query(Int::class.java)
            .single() > 0

    /**
     * Inserts new partition assignment with initial version 0.
     */
    private fun insertPartition(entity: JdbcOutboxPartitionAssignmentEntity) {
        jdbcClient
            .sql(insertPartitionQuery.trimIndent())
            .param(entity.partitionNumber)
            .param(entity.instanceId)
            .param(0) // Initial version is 0
            .param(entity.updatedAt)
            .update()
    }
}
