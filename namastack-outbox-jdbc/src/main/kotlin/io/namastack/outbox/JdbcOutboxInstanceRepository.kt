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
        SET hostname = :hostname, port = :port, status = :status, started_at = :startedAt,
            last_heartbeat = :lastHeartbeat, created_at = :createdAt, updated_at = :updatedAt
        WHERE instance_id = :instanceId
    """

    /**
     * Query to insert a new instance.
     */
    private val insertInstanceQuery = """
        INSERT INTO outbox_instance
        (instance_id, hostname, port, status, started_at, last_heartbeat, created_at, updated_at)
        VALUES (:instanceId, :hostname, :port, :status, :startedAt, :lastHeartbeat, :createdAt, :updatedAt)
    """

    /**
     * Query to select instance by ID.
     */
    private val findByIdQuery = """
        SELECT * FROM outbox_instance
        WHERE instance_id = :instanceId
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
        WHERE status = :status
        ORDER BY last_heartbeat DESC
    """

    /**
     * Query to select instances with stale heartbeat.
     */
    private val findStaleHeartbeatQueryTemplate = """
        SELECT * FROM outbox_instance
        WHERE last_heartbeat < :cutoffTime
          AND status IN (:statuses)
        ORDER BY last_heartbeat
    """

    /**
     * Query to update instance heartbeat.
     */
    private val updateHeartbeatQuery = """
        UPDATE outbox_instance
        SET last_heartbeat = :lastHeartbeat, updated_at = :updatedAt
        WHERE instance_id = :instanceId
    """

    /**
     * Query to update instance status.
     */
    private val updateStatusQuery = """
        UPDATE outbox_instance
        SET status = :status, updated_at = :updatedAt
        WHERE instance_id = :instanceId
    """

    /**
     * Query to count instances by status.
     */
    private val countByStatusQuery = """
        SELECT COUNT(*) FROM outbox_instance
        WHERE status = :status
    """

    /**
     * Query to delete instance by ID.
     */
    private val deleteByIdQuery = """
        DELETE FROM outbox_instance
        WHERE instance_id = :instanceId
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
            .param("instanceId", instanceId)
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
            .param("status", status.name)
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
        val activeStatuses = listOf(OutboxInstanceStatus.ACTIVE.name, OutboxInstanceStatus.SHUTTING_DOWN.name)

        return jdbcClient
            .sql(findStaleHeartbeatQueryTemplate.trimIndent())
            .param("cutoffTime", cutoffTime)
            .param("statuses", activeStatuses)
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
                    .param("lastHeartbeat", timestamp)
                    .param("updatedAt", timestamp)
                    .param("instanceId", instanceId)
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
                    .param("status", status.name)
                    .param("updatedAt", timestamp)
                    .param("instanceId", instanceId)
                    .update()
            updated > 0
        } ?: false

    /**
     * Counts instances by status.
     */
    override fun countByStatus(status: OutboxInstanceStatus): Long =
        jdbcClient
            .sql(countByStatusQuery)
            .param("status", status.name)
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
                    .param("instanceId", instanceId)
                    .update()
            deleted > 0
        } ?: false

    /**
     * Attempts to update an existing instance. Returns number of rows updated.
     */
    private fun tryUpdate(entity: JdbcOutboxInstanceEntity): Int =
        jdbcClient
            .sql(updateInstanceQuery.trimIndent())
            .param("hostname", entity.hostname)
            .param("port", entity.port)
            .param("status", entity.status.name)
            .param("startedAt", entity.startedAt)
            .param("lastHeartbeat", entity.lastHeartbeat)
            .param("createdAt", entity.createdAt)
            .param("updatedAt", entity.updatedAt)
            .param("instanceId", entity.instanceId)
            .update()

    /**
     * Inserts a new instance into the database.
     */
    private fun insert(entity: JdbcOutboxInstanceEntity) {
        jdbcClient
            .sql(insertInstanceQuery.trimIndent())
            .param("instanceId", entity.instanceId)
            .param("hostname", entity.hostname)
            .param("port", entity.port)
            .param("status", entity.status.name)
            .param("startedAt", entity.startedAt)
            .param("lastHeartbeat", entity.lastHeartbeat)
            .param("createdAt", entity.createdAt)
            .param("updatedAt", entity.updatedAt)
            .update()
    }
}
