package io.namastack.outbox

import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * JPA repository implementation for managing outbox instance entities.
 *
 * Provides database operations for instance registration, heartbeat updates,
 * and cleanup of dead instances.
 *
 * @param entityManager JPA entity manager for database operations
 *
 * @author Roland Beisel
 * @since 0.2.0
 */
internal open class JpaOutboxInstanceRepository(
    private val entityManager: EntityManager,
) : OutboxInstanceRepository {
    @Transactional
    override fun save(instance: OutboxInstance): OutboxInstance {
        val entity = OutboxInstanceEntityMapper.toEntity(instance)
        val existingEntity = entityManager.find(OutboxInstanceEntity::class.java, entity.instanceId)

        val savedEntity =
            if (existingEntity != null) {
                entityManager.merge(entity)
            } else {
                entityManager.persist(entity)
                entity
            }

        return OutboxInstanceEntityMapper.fromEntity(savedEntity)
    }

    override fun findById(instanceId: String): OutboxInstance? {
        val entity = entityManager.find(OutboxInstanceEntity::class.java, instanceId)
        return entity?.let { OutboxInstanceEntityMapper.fromEntity(it) }
    }

    override fun findAll(): List<OutboxInstance> {
        val query = """
            SELECT i FROM OutboxInstanceEntity i
            ORDER BY i.createdAt ASC
        """

        val entities =
            entityManager
                .createQuery(query, OutboxInstanceEntity::class.java)
                .resultList

        return OutboxInstanceEntityMapper.fromEntities(entities)
    }

    override fun findByStatus(status: OutboxInstanceStatus): List<OutboxInstance> {
        val query = """
            SELECT i FROM OutboxInstanceEntity i
            WHERE i.status = :status
            ORDER BY i.lastHeartbeat DESC
        """

        val entities =
            entityManager
                .createQuery(query, OutboxInstanceEntity::class.java)
                .setParameter("status", status)
                .resultList

        return OutboxInstanceEntityMapper.fromEntities(entities)
    }

    override fun findActiveInstances(): List<OutboxInstance> = findByStatus(OutboxInstanceStatus.ACTIVE)

    override fun findInstancesWithStaleHeartbeat(cutoffTime: OffsetDateTime): List<OutboxInstance> {
        val query = """
            SELECT i FROM OutboxInstanceEntity i
            WHERE i.lastHeartbeat < :cutoffTime
            AND i.status IN (:activeStatuses)
            ORDER BY i.lastHeartbeat ASC
        """

        val entities =
            entityManager
                .createQuery(query, OutboxInstanceEntity::class.java)
                .setParameter("cutoffTime", cutoffTime)
                .setParameter("activeStatuses", listOf(OutboxInstanceStatus.ACTIVE, OutboxInstanceStatus.SHUTTING_DOWN))
                .resultList

        return OutboxInstanceEntityMapper.fromEntities(entities)
    }

    @Transactional
    override fun updateHeartbeat(
        instanceId: String,
        timestamp: OffsetDateTime,
    ): Boolean {
        val entity =
            entityManager.find(
                OutboxInstanceEntity::class.java,
                instanceId,
                LockModeType.PESSIMISTIC_WRITE,
            ) ?: return false

        entity.updateHeartbeat(timestamp)
        return true
    }

    @Transactional
    override fun updateStatus(
        instanceId: String,
        status: OutboxInstanceStatus,
        timestamp: OffsetDateTime,
    ): Boolean {
        val entity =
            entityManager.find(
                OutboxInstanceEntity::class.java,
                instanceId,
                LockModeType.PESSIMISTIC_WRITE,
            ) ?: return false

        entity.updateStatus(status, timestamp)
        return true
    }

    @Transactional
    override fun deleteById(instanceId: String): Boolean {
        val entity =
            entityManager.find(OutboxInstanceEntity::class.java, instanceId)
                ?: return false

        entityManager.remove(entity)
        return true
    }

    @Transactional
    override fun deleteByStatus(status: OutboxInstanceStatus): Int {
        val query = """
            DELETE FROM OutboxInstanceEntity i
            WHERE i.status = :status
        """

        return entityManager
            .createQuery(query)
            .setParameter("status", status)
            .executeUpdate()
    }

    @Transactional
    override fun deleteStaleInstances(cutoffTime: OffsetDateTime): Int {
        val query = """
            DELETE FROM OutboxInstanceEntity i
            WHERE i.lastHeartbeat < :cutoffTime
        """

        return entityManager
            .createQuery(query)
            .setParameter("cutoffTime", cutoffTime)
            .executeUpdate()
    }

    override fun count(): Long {
        val query = "SELECT COUNT(i) FROM OutboxInstanceEntity i"

        return entityManager
            .createQuery(query, Long::class.java)
            .singleResult
    }

    override fun countByStatus(status: OutboxInstanceStatus): Long {
        val query = """
            SELECT COUNT(i) FROM OutboxInstanceEntity i
            WHERE i.status = :status
        """

        return entityManager
            .createQuery(query, Long::class.java)
            .setParameter("status", status)
            .singleResult
    }
}
