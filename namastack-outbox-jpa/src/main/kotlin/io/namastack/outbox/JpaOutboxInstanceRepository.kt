package io.namastack.outbox

import io.namastack.outbox.instance.OutboxInstance
import io.namastack.outbox.instance.OutboxInstanceRepository
import io.namastack.outbox.instance.OutboxInstanceStatus
import io.namastack.outbox.instance.OutboxInstanceStatus.ACTIVE
import io.namastack.outbox.instance.OutboxInstanceStatus.SHUTTING_DOWN
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/**
 * JPA repository implementation for managing outbox instance entities.
 *
 * Provides database operations for instance registration, heartbeat updates,
 * and cleanup of dead instances.
 *
 * @param entityManager JPA entity manager for database operations
 * @param transactionTemplate Transaction template for programmatic transaction management
 *
 * @author Roland Beisel
 * @since 0.2.0
 */
internal open class JpaOutboxInstanceRepository(
    private val entityManager: EntityManager,
    private val transactionTemplate: TransactionTemplate,
) : OutboxInstanceRepository {
    /**
     * Query to select all outbox instance entities ordered by creation time.
     */
    private val findAllQuery = """
        select o from OutboxInstanceEntity o
        order by o.createdAt asc
    """

    /**
     * Query to select outbox instance entities by status ordered by last heartbeat descending.
     */
    private val findByStatusQuery = """
        select o from OutboxInstanceEntity o
        where o.status = :status
        order by o.lastHeartbeat desc
    """

    /**
     * Query to select outbox instance entities with stale heartbeat.
     */
    private val findInstancesWithStaleHeartbeatQuery = """
        select o from OutboxInstanceEntity o
        where o.lastHeartbeat < :cutoffTime
        and o.status in (:activeStatuses)
        order by o.lastHeartbeat asc
    """

    /**
     * Query to count outbox instance entities by status.
     */
    private val countByStatusQuery = """
        select count(o) from OutboxInstanceEntity o
        where o.status = :status
    """

    /**
     * Saves an outbox instance entity to the database.
     *
     * If an entity with the same instanceId exists, it is updated (merged).
     * Otherwise, a new entity is inserted.
     *
     * @param instance The outbox instance to save
     * @return The saved outbox instance
     */
    override fun save(instance: OutboxInstance): OutboxInstance =
        transactionTemplate.execute {
            val entity = OutboxInstanceEntityMapper.toEntity(instance)
            val existingEntity = entityManager.find(OutboxInstanceEntity::class.java, entity.instanceId)

            val savedEntity =
                if (existingEntity != null) {
                    entityManager.merge(entity)
                } else {
                    entityManager.persist(entity)
                    entity
                }

            OutboxInstanceEntityMapper.fromEntity(savedEntity)
        }

    /**
     * Finds an outbox instance entity by its unique instanceId.
     *
     * @param instanceId The unique identifier of the outbox instance
     * @return The found outbox instance or null if not found
     */
    override fun findById(instanceId: String): OutboxInstance? {
        val entity = entityManager.find(OutboxInstanceEntity::class.java, instanceId)
        return entity?.let { OutboxInstanceEntityMapper.fromEntity(it) }
    }

    /**
     * Finds all outbox instance entities ordered by creation time.
     *
     * @return List of all outbox instances
     */
    override fun findAll(): List<OutboxInstance> {
        val entities =
            entityManager
                .createQuery(findAllQuery, OutboxInstanceEntity::class.java)
                .resultList

        return OutboxInstanceEntityMapper.fromEntities(entities)
    }

    /**
     * Finds all outbox instance entities with the given status, ordered by last heartbeat descending.
     *
     * @param status The status to filter by
     * @return List of outbox instances with the given status
     */
    override fun findByStatus(status: OutboxInstanceStatus): List<OutboxInstance> {
        val entities =
            entityManager
                .createQuery(findByStatusQuery, OutboxInstanceEntity::class.java)
                .setParameter("status", status)
                .resultList

        return OutboxInstanceEntityMapper.fromEntities(entities)
    }

    /**
     * Finds all active outbox instance entities.
     *
     * @return List of active outbox instances
     */
    override fun findActiveInstances(): List<OutboxInstance> = findByStatus(ACTIVE)

    /**
     * Finds all outbox instance entities with a stale heartbeat (older than cutoffTime).
     *
     * @param cutoffTime The cutoff time for stale heartbeat
     * @return List of outbox instances with stale heartbeat
     */
    override fun findInstancesWithStaleHeartbeat(cutoffTime: Instant): List<OutboxInstance> {
        val entities =
            entityManager
                .createQuery(findInstancesWithStaleHeartbeatQuery, OutboxInstanceEntity::class.java)
                .setParameter("cutoffTime", cutoffTime)
                .setParameter("activeStatuses", listOf(ACTIVE, SHUTTING_DOWN))
                .resultList

        return OutboxInstanceEntityMapper.fromEntities(entities)
    }

    /**
     * Updates the heartbeat timestamp for the given instance.
     *
     * @param instanceId The unique identifier of the outbox instance
     * @param timestamp The new heartbeat timestamp
     * @return true if the update was successful, false otherwise
     */
    override fun updateHeartbeat(
        instanceId: String,
        timestamp: Instant,
    ): Boolean =
        transactionTemplate.execute {
            val entity =
                entityManager.find(
                    OutboxInstanceEntity::class.java,
                    instanceId,
                    LockModeType.PESSIMISTIC_WRITE,
                ) ?: return@execute false

            entity.updateHeartbeat(timestamp)
            true
        }

    /**
     * Updates the status and timestamp for the given instance.
     *
     * @param instanceId The unique identifier of the outbox instance
     * @param status The new status
     * @param timestamp The new status timestamp
     * @return true if the update was successful, false otherwise
     */
    override fun updateStatus(
        instanceId: String,
        status: OutboxInstanceStatus,
        timestamp: Instant,
    ): Boolean =
        transactionTemplate.execute {
            val entity =
                entityManager.find(
                    OutboxInstanceEntity::class.java,
                    instanceId,
                    LockModeType.PESSIMISTIC_WRITE,
                ) ?: return@execute false

            entity.updateStatus(status, timestamp)
            true
        }

    /**
     * Deletes an outbox instance entity by its unique instanceId.
     *
     * @param instanceId The unique identifier of the outbox instance
     * @return true if the deletion was successful, false otherwise
     */
    override fun deleteById(instanceId: String): Boolean =
        transactionTemplate.execute {
            val entity =
                entityManager.find(OutboxInstanceEntity::class.java, instanceId)
                    ?: return@execute false

            entityManager.remove(entity)
            true
        }

    /**
     * Counts the number of outbox instance entities with the given status.
     *
     * @param status The status to count
     * @return The number of outbox instances with the given status
     */
    override fun countByStatus(status: OutboxInstanceStatus): Long =
        entityManager
            .createQuery(countByStatusQuery, Long::class.java)
            .setParameter("status", status)
            .singleResult
}
