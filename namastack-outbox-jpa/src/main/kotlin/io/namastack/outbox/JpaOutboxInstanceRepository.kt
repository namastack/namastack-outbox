package io.namastack.outbox

import io.namastack.outbox.instance.OutboxInstance
import io.namastack.outbox.instance.OutboxInstanceRepository
import io.namastack.outbox.instance.OutboxInstanceStatus
import io.namastack.outbox.instance.OutboxInstanceStatus.ACTIVE
import io.namastack.outbox.instance.OutboxInstanceStatus.SHUTTING_DOWN
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime

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
    override fun save(instance: OutboxInstance): OutboxInstance =
        transactionTemplate.executeNonNull {
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

    override fun findById(instanceId: String): OutboxInstance? {
        val entity = entityManager.find(OutboxInstanceEntity::class.java, instanceId)
        return entity?.let { OutboxInstanceEntityMapper.fromEntity(it) }
    }

    override fun findAll(): List<OutboxInstance> {
        val query = """
            select o from OutboxInstanceEntity o
            order by o.createdAt asc
        """

        val entities =
            entityManager
                .createQuery(query, OutboxInstanceEntity::class.java)
                .resultList

        return OutboxInstanceEntityMapper.fromEntities(entities)
    }

    override fun findByStatus(status: OutboxInstanceStatus): List<OutboxInstance> {
        val query = """
            select o from OutboxInstanceEntity o
            where o.status = :status
            order by o.lastHeartbeat desc
        """

        val entities =
            entityManager
                .createQuery(query, OutboxInstanceEntity::class.java)
                .setParameter("status", status)
                .resultList

        return OutboxInstanceEntityMapper.fromEntities(entities)
    }

    override fun findActiveInstances(): List<OutboxInstance> = findByStatus(ACTIVE)

    override fun findInstancesWithStaleHeartbeat(cutoffTime: OffsetDateTime): List<OutboxInstance> {
        val query = """
            select o from OutboxInstanceEntity o
            where o.lastHeartbeat < :cutoffTime
            and o.status in (:activeStatuses)
            order by o.lastHeartbeat asc
        """

        val entities =
            entityManager
                .createQuery(query, OutboxInstanceEntity::class.java)
                .setParameter("cutoffTime", cutoffTime)
                .setParameter("activeStatuses", listOf(ACTIVE, SHUTTING_DOWN))
                .resultList

        return OutboxInstanceEntityMapper.fromEntities(entities)
    }

    override fun updateHeartbeat(
        instanceId: String,
        timestamp: OffsetDateTime,
    ): Boolean =
        transactionTemplate.executeNonNull {
            val entity =
                entityManager.find(
                    OutboxInstanceEntity::class.java,
                    instanceId,
                    LockModeType.PESSIMISTIC_WRITE,
                ) ?: return@executeNonNull false

            entity.updateHeartbeat(timestamp)
            true
        }

    override fun updateStatus(
        instanceId: String,
        status: OutboxInstanceStatus,
        timestamp: OffsetDateTime,
    ): Boolean =
        transactionTemplate.executeNonNull {
            val entity =
                entityManager.find(
                    OutboxInstanceEntity::class.java,
                    instanceId,
                    LockModeType.PESSIMISTIC_WRITE,
                ) ?: return@executeNonNull false

            entity.updateStatus(status, timestamp)
            true
        }

    override fun deleteById(instanceId: String): Boolean =
        transactionTemplate.executeNonNull {
            val entity =
                entityManager.find(OutboxInstanceEntity::class.java, instanceId)
                    ?: return@executeNonNull false

            entityManager.remove(entity)
            true
        }

    override fun deleteByStatus(status: OutboxInstanceStatus): Int =
        transactionTemplate.executeNonNull {
            val query = """
            delete from OutboxInstanceEntity o
            where o.status = :status
        """

            entityManager
                .createQuery(query)
                .setParameter("status", status)
                .executeUpdate()
        }

    override fun deleteStaleInstances(cutoffTime: OffsetDateTime): Int =
        transactionTemplate.executeNonNull {
            val query = """
            delete from OutboxInstanceEntity o
            where o.lastHeartbeat < :cutoffTime
        """

            entityManager
                .createQuery(query)
                .setParameter("cutoffTime", cutoffTime)
                .executeUpdate()
        }

    override fun count(): Long {
        val query = "select count(o) from OutboxInstanceEntity o"

        return entityManager
            .createQuery(query, Long::class.java)
            .singleResult
    }

    override fun countByStatus(status: OutboxInstanceStatus): Long {
        val query = """
            select count(o) from OutboxInstanceEntity o
            where o.status = :status
        """

        return entityManager
            .createQuery(query, Long::class.java)
            .setParameter("status", status)
            .singleResult
    }
}
