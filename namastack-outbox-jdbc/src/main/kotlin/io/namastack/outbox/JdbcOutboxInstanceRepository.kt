package io.namastack.outbox

import io.namastack.outbox.instance.OutboxInstance
import io.namastack.outbox.instance.OutboxInstanceRepository
import io.namastack.outbox.instance.OutboxInstanceStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.queryForObject
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.time.OffsetDateTime

/**
 * JDBC repository implementation for managing outbox instance entities.
 *
 * Provides database operations for instance registration, heartbeat updates,
 * and cleanup of dead instances.
 *
 * @param jdbcTemplate JDBC template for database operations
 * @param transactionTemplate Transaction template for programmatic transaction management
 *
 * @author Roland Beisel
 * @since 1.1.0
 */
internal open class JdbcOutboxInstanceRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
) : OutboxInstanceRepository {
    private val rowMapper =
        RowMapper { rs: ResultSet, _: Int ->
            OutboxInstance(
                instanceId = rs.getString("instance_id"),
                hostname = rs.getString("hostname"),
                port = rs.getInt("port"),
                status = OutboxInstanceStatus.valueOf(rs.getString("status")),
                startedAt = rs.getObject("started_at", OffsetDateTime::class.java),
                lastHeartbeat = rs.getObject("last_heartbeat", OffsetDateTime::class.java),
                createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
                updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java),
            )
        }

    override fun save(instance: OutboxInstance): OutboxInstance =
        transactionTemplate.execute {
            // Try update first within transaction
            val updated =
                jdbcTemplate.update(
                    """
                    UPDATE outbox_instance
                    SET hostname = ?, port = ?, status = ?, started_at = ?,
                        last_heartbeat = ?, created_at = ?, updated_at = ?
                    WHERE instance_id = ?
                    """.trimIndent(),
                    instance.hostname,
                    instance.port,
                    instance.status.name,
                    instance.startedAt,
                    instance.lastHeartbeat,
                    instance.createdAt,
                    instance.updatedAt,
                    instance.instanceId,
                )

            // If no rows updated, insert
            if (updated == 0) {
                jdbcTemplate.update(
                    """
                    INSERT INTO outbox_instance
                    (instance_id, hostname, port, status, started_at, last_heartbeat, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    instance.instanceId,
                    instance.hostname,
                    instance.port,
                    instance.status.name,
                    instance.startedAt,
                    instance.lastHeartbeat,
                    instance.createdAt,
                    instance.updatedAt,
                )
            }
            instance
        }!!

    override fun findById(instanceId: String): OutboxInstance? =
        jdbcTemplate
            .query(
                "SELECT * FROM outbox_instance WHERE instance_id = ?",
                rowMapper,
                instanceId,
            ).firstOrNull()

    override fun findAll(): List<OutboxInstance> =
        jdbcTemplate.query(
            "SELECT * FROM outbox_instance ORDER BY created_at",
            rowMapper,
        )

    override fun findByStatus(status: OutboxInstanceStatus): List<OutboxInstance> =
        jdbcTemplate.query(
            "SELECT * FROM outbox_instance WHERE status = ? ORDER BY last_heartbeat DESC",
            rowMapper,
            status.name,
        )

    override fun findActiveInstances(): List<OutboxInstance> = findByStatus(OutboxInstanceStatus.ACTIVE)

    override fun findInstancesWithStaleHeartbeat(cutoffTime: OffsetDateTime): List<OutboxInstance> {
        val activeStatuses = listOf(OutboxInstanceStatus.ACTIVE, OutboxInstanceStatus.SHUTTING_DOWN)
        val statusPlaceholders = activeStatuses.joinToString(",") { "?" }
        val args = mutableListOf<Any>()
        args.add(cutoffTime)
        args.addAll(activeStatuses.map { it.name })

        return jdbcTemplate.query(
            """
            SELECT * FROM outbox_instance
            WHERE last_heartbeat < ?
              AND status IN ($statusPlaceholders)
            ORDER BY last_heartbeat
            """.trimIndent(),
            rowMapper,
            *args.toTypedArray(),
        )
    }

    override fun updateHeartbeat(
        instanceId: String,
        timestamp: OffsetDateTime,
    ): Boolean =
        transactionTemplate.execute {
            val updated =
                jdbcTemplate.update(
                    """
                    UPDATE outbox_instance
                    SET last_heartbeat = ?, updated_at = ?
                    WHERE instance_id = ?
                    """.trimIndent(),
                    timestamp,
                    timestamp,
                    instanceId,
                )
            updated > 0
        } ?: false

    override fun updateStatus(
        instanceId: String,
        status: OutboxInstanceStatus,
        timestamp: OffsetDateTime,
    ): Boolean =
        transactionTemplate.execute {
            val updated =
                jdbcTemplate.update(
                    """
                    UPDATE outbox_instance
                    SET status = ?, updated_at = ?
                    WHERE instance_id = ?
                    """.trimIndent(),
                    status.name,
                    timestamp,
                    instanceId,
                )
            updated > 0
        } ?: false

    override fun countByStatus(status: OutboxInstanceStatus): Long =
        jdbcTemplate.queryForObject<Long>(
            "SELECT COUNT(*) FROM outbox_instance WHERE status = ?",
            status.name,
        ) ?: 0L

    override fun deleteById(instanceId: String): Boolean {
        var deleted = false
        transactionTemplate.executeWithoutResult {
            val rows =
                jdbcTemplate.update(
                    "DELETE FROM outbox_instance WHERE instance_id = ?",
                    instanceId,
                )
            deleted = rows > 0
        }
        return deleted
    }
}
