package com.beisel.springoutbox

import com.beisel.springoutbox.OutboxRecordEntityMapper.map
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import java.time.Clock
import java.time.OffsetDateTime

internal open class JpaOutboxRecordRepository(
    private val entityManager: EntityManager,
    private val clock: Clock,
) : OutboxRecordRepository,
    OutboxRecordStatusRepository {
    @Transactional
    override fun save(record: OutboxRecord): OutboxRecord {
        val entity = map(record)

        val existingEntity = entityManager.find(OutboxRecordEntity::class.java, entity.id)

        if (existingEntity != null) {
            entityManager.merge(entity)
        } else {
            entityManager.persist(entity)
        }

        return record
    }

    override fun findPendingRecords(): List<OutboxRecord> {
        val query = """
            select o
            from OutboxRecordEntity o
            where o.status = 'NEW'
            order by o.createdAt asc
        """

        return entityManager
            .createQuery(query, OutboxRecordEntity::class.java)
            .resultList
            .map { map(it) }
    }

    override fun findCompletedRecords(): List<OutboxRecord> {
        val query = """
            select o
            from OutboxRecordEntity o
            where o.status = 'COMPLETED'
            order by o.createdAt asc
        """

        return entityManager
            .createQuery(query, OutboxRecordEntity::class.java)
            .resultList
            .map { map(it) }
    }

    override fun findFailedRecords(): List<OutboxRecord> {
        val query = """
            select o
            from OutboxRecordEntity o
            where o.status = 'FAILED'
            order by o.createdAt asc
        """

        return entityManager
            .createQuery(query, OutboxRecordEntity::class.java)
            .resultList
            .map { map(it) }
    }

    override fun findAggregateIdsWithPendingRecords(status: OutboxRecordStatus): List<String> {
        val query = """
            select distinct o.aggregateId
            from OutboxRecordEntity o
            where
                o.status = :status
                and o.nextRetryAt <= :now
        """

        return entityManager
            .createQuery(query, String::class.java)
            .setParameter("status", status)
            .setParameter("now", OffsetDateTime.now(clock))
            .resultList
    }

    override fun findAggregateIdsWithFailedRecords(): List<String> {
        val query = """
            select distinct aggregateId
            from OutboxRecordEntity
            where status = :failedStatus
    """

        return entityManager
            .createQuery(query, String::class.java)
            .setParameter("failedStatus", OutboxRecordStatus.FAILED)
            .resultList
    }

    override fun findAllIncompleteRecordsByAggregateId(aggregateId: String): List<OutboxRecord> {
        val query = """
            select o
            from OutboxRecordEntity o
            where
                o.aggregateId = :aggregateId
                and o.completedAt is null
            order by o.createdAt asc
        """

        return entityManager
            .createQuery(query, OutboxRecordEntity::class.java)
            .setParameter("aggregateId", aggregateId)
            .resultList
            .map { map(it) }
    }

    override fun countByStatus(status: OutboxRecordStatus): Long {
        val query = """
            select count(o)
            from OutboxRecordEntity o
            where o.status = :status
        """

        return entityManager
            .createQuery(query, Long::class.java)
            .setParameter("status", status)
            .singleResult
    }
}
