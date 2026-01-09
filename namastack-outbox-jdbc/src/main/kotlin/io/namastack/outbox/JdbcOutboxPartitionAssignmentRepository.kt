package io.namastack.outbox

import io.namastack.outbox.partition.PartitionAssignment
import io.namastack.outbox.partition.PartitionAssignmentRepository
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.time.OffsetDateTime

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
 * @since 1.1.0
 */
internal open class JdbcOutboxPartitionAssignmentRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
) : PartitionAssignmentRepository {
    private val rowMapper =
        RowMapper { rs: ResultSet, _: Int ->
            PartitionAssignment(
                partitionNumber = rs.getInt("partition_number"),
                instanceId = rs.getString("instance_id"),
                updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java),
                version = rs.getLong("version"),
            )
        }

    override fun findAll(): Set<PartitionAssignment> =
        jdbcTemplate
            .query(
                "SELECT * FROM outbox_partition ORDER BY partition_number",
                rowMapper,
            ).toSet()

    override fun findByInstanceId(instanceId: String): Set<PartitionAssignment> =
        jdbcTemplate
            .query(
                "SELECT * FROM outbox_partition WHERE instance_id = ?",
                rowMapper,
                instanceId,
            ).toSet()

    override fun saveAll(partitionAssignments: Set<PartitionAssignment>) {
        transactionTemplate.execute {
            partitionAssignments.forEach { assignment ->
                // Calculate new version (increment by 1)
                val newVersion = (assignment.version ?: 0) + 1

                // Try UPDATE with optimistic locking (version check in WHERE clause)
                val updated =
                    jdbcTemplate.update(
                        """
                        UPDATE outbox_partition
                        SET instance_id = ?, version = ?, updated_at = ?
                        WHERE partition_number = ? AND version = ?
                        """.trimIndent(),
                        assignment.instanceId,
                        newVersion,
                        assignment.updatedAt,
                        assignment.partitionNumber,
                        assignment.version ?: 0,
                    )

                // If no rows updated, either record doesn't exist or version mismatch
                if (updated == 0) {
                    // Check if record exists
                    val exists =
                        (
                            jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM outbox_partition WHERE partition_number = ?",
                                Int::class.java,
                                assignment.partitionNumber,
                            ) ?: 0
                        ) > 0

                    if (exists) {
                        // Record exists but version mismatch -> Optimistic locking failure
                        throw OptimisticLockingFailureException(
                            "Partition assignment with partition number ${assignment.partitionNumber} was updated by another instance (version mismatch)",
                        )
                    } else {
                        // Record doesn't exist -> INSERT
                        jdbcTemplate.update(
                            """
                            INSERT INTO outbox_partition (partition_number, instance_id, version, updated_at)
                            VALUES (?, ?, ?, ?)
                            """.trimIndent(),
                            assignment.partitionNumber,
                            assignment.instanceId,
                            0, // Initial version is 0
                            assignment.updatedAt,
                        )
                    }
                }
            }
        }
    }
}
