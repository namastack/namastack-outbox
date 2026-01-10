package io.namastack.outbox

import io.namastack.outbox.partition.PartitionAssignment
import io.namastack.outbox.partition.PartitionAssignmentRepository
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import org.springframework.transaction.support.TransactionTemplate

/**
 * JDBC repository implementation for managing partition assignments.
 *
 * Uses optimistic locking (version field) to detect and prevent concurrent
 * modifications when multiple instances try to claim the same partition.
 *
 * @param jdbcTemplate JDBC template for database operations
 * @param transactionTemplate Transaction template for programmatic transaction management
 *
 * @author Roland Beisel
 * @since 1.0.0
 */
internal open class JdbcOutboxPartitionAssignmentRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
) : PartitionAssignmentRepository {
    private val rowMapper = PartitionAssignmentRowMapper()

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
    override fun findAll(): Set<PartitionAssignment> = jdbcTemplate.query(findAllQuery, rowMapper).toSet()

    /**
     * Finds partition assignments by instance ID.
     */
    override fun findByInstanceId(instanceId: String): Set<PartitionAssignment> =
        jdbcTemplate.query(findByInstanceIdQuery, rowMapper, instanceId).toSet()

    /**
     * Saves all partition assignments with optimistic locking.
     * Throws OptimisticLockingFailureException if version mismatch detected.
     */
    override fun saveAll(partitionAssignments: Set<PartitionAssignment>) {
        transactionTemplate.execute {
            partitionAssignments.forEach { assignment ->
                savePartitionAssignment(assignment)
            }
        }
    }

    /**
     * Saves a single partition assignment.
     * Tries update first, then insert if not exists, or throws exception if version mismatch.
     */
    private fun savePartitionAssignment(assignment: PartitionAssignment) {
        val newVersion = (assignment.version ?: 0) + 1
        val updated = tryUpdate(assignment, newVersion)

        if (updated == 0) {
            handleUpdateFailure(assignment)
        }
    }

    /**
     * Attempts to update partition assignment with optimistic locking.
     * Returns number of rows updated.
     */
    private fun tryUpdate(
        assignment: PartitionAssignment,
        newVersion: Long,
    ): Int =
        jdbcTemplate.update(
            updatePartitionQuery.trimIndent(),
            assignment.instanceId,
            newVersion,
            assignment.updatedAt,
            assignment.partitionNumber,
            assignment.version ?: 0,
        )

    /**
     * Handles case when update fails - either partition doesn't exist (insert) or version mismatch (exception).
     */
    private fun handleUpdateFailure(assignment: PartitionAssignment) {
        if (partitionExists(assignment.partitionNumber)) {
            throw OptimisticLockingFailureException(
                "Partition assignment with partition number ${assignment.partitionNumber} " +
                    "was updated by another instance (version mismatch)",
            )
        } else {
            insertPartition(assignment)
        }
    }

    /**
     * Checks if partition exists in database.
     */
    private fun partitionExists(partitionNumber: Int): Boolean =
        (jdbcTemplate.queryForObject<Int>(partitionExistsQuery, partitionNumber) ?: 0) > 0

    /**
     * Inserts new partition assignment with initial version 0.
     */
    private fun insertPartition(assignment: PartitionAssignment) {
        jdbcTemplate.update(
            insertPartitionQuery.trimIndent(),
            assignment.partitionNumber,
            assignment.instanceId,
            0, // Initial version is 0
            assignment.updatedAt,
        )
    }
}
