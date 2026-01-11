package io.namastack.outbox

import io.namastack.outbox.instance.OutboxInstance
import io.namastack.outbox.instance.OutboxInstanceRepository
import io.namastack.outbox.instance.OutboxInstanceStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime

/**
 * JDBC repository implementation for managing outbox instance entities.
 *
 * Provides database operations for instance registration, heartbeat updates,
 * and cleanup of dead instances.
 *
 * @param jdbcClient JDBC client for database operations
 * @param transactionTemplate Transaction template for programmatic transaction management
 *
 * @author Roland Beisel
 * @since 1.1.0
 */
internal open class JdbcOutboxInstanceRepository(
    private val jdbcClient: JdbcClient,
    private val transactionTemplate: TransactionTemplate,
) : OutboxInstanceRepository {
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
            val entity = JdbcOutboxInstanceEntityMapper.map(instance)
            val updated = tryUpdate(entity)
            if (updated == 0) {
                insert(entity)
            }
            instance
        }

    /**
     * Finds instance by ID.
     */
    override fun findById(instanceId: String): OutboxInstance? =
        jdbcClient
            .sql(findByIdQuery)
            .param(instanceId)
            .query(JdbcOutboxInstanceEntity::class.java)
            .optional()
            .map { JdbcOutboxInstanceEntityMapper.map(it) }
            .orElse(null)

    /**
     * Finds all instances ordered by creation time.
     */
    override fun findAll(): List<OutboxInstance> =
        jdbcClient
            .sql(findAllQuery)
            .query(JdbcOutboxInstanceEntity::class.java)
            .list()
            .filterNotNull()
            .map { JdbcOutboxInstanceEntityMapper.map(it) }

    /**
     * Finds instances by status ordered by last heartbeat.
     */
    override fun findByStatus(status: OutboxInstanceStatus): List<OutboxInstance> =
        jdbcClient
            .sql(findByStatusQuery)
            .param(status.name)
            .query(JdbcOutboxInstanceEntity::class.java)
            .list()
            .filterNotNull()
            .map { JdbcOutboxInstanceEntityMapper.map(it) }

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

        val clientBuilder = jdbcClient.sql(query).param(cutoffTime)
        activeStatuses.forEach { clientBuilder.param(it.name) }

        return clientBuilder
            .query(JdbcOutboxInstanceEntity::class.java)
            .list()
            .filterNotNull()
            .map { JdbcOutboxInstanceEntityMapper.map(it) }
    }

    /**
     * Updates instance heartbeat timestamp.
     */
    override fun updateHeartbeat(
        instanceId: String,
        timestamp: OffsetDateTime,
    ): Boolean =
        transactionTemplate.execute {
            val updated =
                jdbcClient
                    .sql(updateHeartbeatQuery.trimIndent())
                    .param(timestamp)
                    .param(timestamp)
                    .param(instanceId)
                    .update()
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
            val updated =
                jdbcClient
                    .sql(updateStatusQuery.trimIndent())
                    .param(status.name)
                    .param(timestamp)
                    .param(instanceId)
                    .update()
            updated > 0
        } ?: false

    /**
     * Counts instances by status.
     */
    override fun countByStatus(status: OutboxInstanceStatus): Long =
        jdbcClient
            .sql(countByStatusQuery)
            .param(status.name)
            .query(Long::class.java)
            .single()

    /**
     * Deletes instance by ID.
     */
    override fun deleteById(instanceId: String): Boolean =
        transactionTemplate.execute {
            val deleted =
                jdbcClient
                    .sql(deleteByIdQuery.trimIndent())
                    .param(instanceId)
                    .update()
            deleted > 0
        } ?: false

    /**
     * Attempts to update an existing instance. Returns number of rows updated.
     */
    private fun tryUpdate(entity: JdbcOutboxInstanceEntity): Int =
        jdbcClient
            .sql(updateInstanceQuery.trimIndent())
            .param(entity.hostname)
            .param(entity.port)
            .param(entity.status.name)
            .param(entity.startedAt)
            .param(entity.lastHeartbeat)
            .param(entity.createdAt)
            .param(entity.updatedAt)
            .param(entity.instanceId)
            .update()

    /**
     * Inserts a new instance into the database.
     */
    private fun insert(entity: JdbcOutboxInstanceEntity) {
        jdbcClient
            .sql(insertInstanceQuery.trimIndent())
            .param(entity.instanceId)
            .param(entity.hostname)
            .param(entity.port)
            .param(entity.status.name)
            .param(entity.startedAt)
            .param(entity.lastHeartbeat)
            .param(entity.createdAt)
            .param(entity.updatedAt)
            .update()
    }
}
