package io.namastack.outbox

import io.namastack.outbox.instance.OutboxInstance
import io.namastack.outbox.instance.OutboxInstanceRepository
import io.namastack.outbox.instance.OutboxInstanceStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
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
 * @since 1.0.0
 */
internal open class JdbcOutboxInstanceRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
) : OutboxInstanceRepository {
    private val rowMapper = OutboxInstanceRowMapper()

    /**
     * Query to update an existing instance.
     */
    private val updateInstanceQuery = """
        UPDATE outbox_instance
        SET hostname = ?, port = ?, status = ?, started_at = ?,
            last_heartbeat = ?, created_at = ?, updated_at = ?
        WHERE instance_id = ?
    """

    /**
     * Query to insert a new instance.
     */
    private val insertInstanceQuery = """
        INSERT INTO outbox_instance
        (instance_id, hostname, port, status, started_at, last_heartbeat, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    """

    /**
     * Query to select instance by ID.
     */
    private val findByIdQuery = """
        SELECT * FROM outbox_instance
        WHERE instance_id = ?
    """

    /**
     * Query to select all instances ordered by creation time.
     */
    private val findAllQuery = """
        SELECT * FROM outbox_instance
        ORDER BY created_at
    """

    /**
     * Query to select instances by status ordered by last heartbeat.
     */
    private val findByStatusQuery = """
        SELECT * FROM outbox_instance
        WHERE status = ?
        ORDER BY last_heartbeat DESC
    """

    /**
     * Query to select instances with stale heartbeat.
     * Note: Status placeholders are dynamically injected.
     */
    private val findStaleHeartbeatQueryTemplate = """
        SELECT * FROM outbox_instance
        WHERE last_heartbeat < ?
          AND status IN (%s)
        ORDER BY last_heartbeat
    """

    /**
     * Query to update instance heartbeat.
     */
    private val updateHeartbeatQuery = """
        UPDATE outbox_instance
        SET last_heartbeat = ?, updated_at = ?
        WHERE instance_id = ?
    """

    /**
     * Query to update instance status.
     */
    private val updateStatusQuery = """
        UPDATE outbox_instance
        SET status = ?, updated_at = ?
        WHERE instance_id = ?
    """

    /**
     * Query to count instances by status.
     */
    private val countByStatusQuery = """
        SELECT COUNT(*) FROM outbox_instance
        WHERE status = ?
    """

    /**
     * Query to delete instance by ID.
     */
    private val deleteByIdQuery = """
        DELETE FROM outbox_instance
        WHERE instance_id = ?
    """

    /**
     * Saves an instance. Updates existing instance or inserts new one.
     */
    override fun save(instance: OutboxInstance): OutboxInstance =
        transactionTemplate.execute {
            val updated = tryUpdate(instance)
            if (updated == 0) {
                insert(instance)
            }
            instance
        }

    /**
     * Finds instance by ID.
     */
    override fun findById(instanceId: String): OutboxInstance? =
        jdbcTemplate.query(findByIdQuery, rowMapper, instanceId).firstOrNull()

    /**
     * Finds all instances ordered by creation time.
     */
    override fun findAll(): List<OutboxInstance> = jdbcTemplate.query(findAllQuery, rowMapper)

    /**
     * Finds instances by status ordered by last heartbeat.
     */
    override fun findByStatus(status: OutboxInstanceStatus): List<OutboxInstance> =
        jdbcTemplate.query(findByStatusQuery, rowMapper, status.name)

    /**
     * Finds all active instances.
     */
    override fun findActiveInstances(): List<OutboxInstance> = findByStatus(OutboxInstanceStatus.ACTIVE)

    /**
     * Finds instances with stale heartbeat.
     */
    override fun findInstancesWithStaleHeartbeat(cutoffTime: OffsetDateTime): List<OutboxInstance> {
        val activeStatuses = listOf(OutboxInstanceStatus.ACTIVE, OutboxInstanceStatus.SHUTTING_DOWN)
        val statusPlaceholders = activeStatuses.joinToString(",") { "?" }
        val query = findStaleHeartbeatQueryTemplate.format(statusPlaceholders).trimIndent()
        val args =
            buildList<Any> {
                add(cutoffTime)
                addAll(activeStatuses.map { it.name })
            }

        return jdbcTemplate.query(query, rowMapper, *args.toTypedArray())
    }

    /**
     * Updates instance heartbeat timestamp.
     */
    override fun updateHeartbeat(
        instanceId: String,
        timestamp: OffsetDateTime,
    ): Boolean =
        transactionTemplate.execute {
            val updated = jdbcTemplate.update(updateHeartbeatQuery.trimIndent(), timestamp, timestamp, instanceId)
            updated > 0
        } ?: false

    /**
     * Updates instance status.
     */
    override fun updateStatus(
        instanceId: String,
        status: OutboxInstanceStatus,
        timestamp: OffsetDateTime,
    ): Boolean =
        transactionTemplate.execute {
            val updated = jdbcTemplate.update(updateStatusQuery.trimIndent(), status.name, timestamp, instanceId)
            updated > 0
        } ?: false

    /**
     * Counts instances by status.
     */
    override fun countByStatus(status: OutboxInstanceStatus): Long =
        jdbcTemplate.queryForObject(countByStatusQuery, Long::class.java, status.name) ?: 0L

    /**
     * Deletes instance by ID.
     */
    override fun deleteById(instanceId: String): Boolean {
        var deleted = false
        transactionTemplate.executeWithoutResult {
            val rows = jdbcTemplate.update(deleteByIdQuery.trimIndent(), instanceId)
            deleted = rows > 0
        }
        return deleted
    }

    /**
     * Attempts to update an existing instance. Returns number of rows updated.
     */
    private fun tryUpdate(instance: OutboxInstance): Int =
        jdbcTemplate.update(
            updateInstanceQuery.trimIndent(),
            instance.hostname,
            instance.port,
            instance.status.name,
            instance.startedAt,
            instance.lastHeartbeat,
            instance.createdAt,
            instance.updatedAt,
            instance.instanceId,
        )

    /**
     * Inserts a new instance into the database.
     */
    private fun insert(instance: OutboxInstance) {
        jdbcTemplate.update(
            insertInstanceQuery.trimIndent(),
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
}
