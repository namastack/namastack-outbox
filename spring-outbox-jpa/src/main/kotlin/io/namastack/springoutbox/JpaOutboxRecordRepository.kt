package io.namastack.springoutbox

import io.namastack.springoutbox.OutboxRecordEntityMapper.map
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import java.time.Clock
import java.time.OffsetDateTime

/**
 * JPA implementation of the OutboxRecordRepository and OutboxRecordStatusRepository interfaces.
 *
 * This implementation uses JPA/Hibernate to persist and query outbox records
 * from a relational database.
 *
 * @param entityManager JPA entity manager for database operations
 * @param clock Clock for time-based operations
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
internal open class JpaOutboxRecordRepository(
    private val entityManager: EntityManager,
    private val clock: Clock,
) : OutboxRecordRepository,
    OutboxRecordStatusRepository {
    /**
     * Saves an outbox record to the database.
     *
     * @param record The outbox record to save
     * @return The saved outbox record
     */
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

    /**
     * Finds all pending outbox records that are ready for processing.
     *
     * @return List of pending outbox records ordered by creation time
     */
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

    /**
     * Finds all completed outbox records.
     *
     * @return List of completed outbox records ordered by creation time
     */
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

    /**
     * Finds all failed outbox records.
     *
     * @return List of failed outbox records ordered by creation time
     */
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

    /**
     * Finds aggregate IDs that have pending records with the specified status.
     *
     * @param status The status to filter by
     * @return List of distinct aggregate IDs with pending records
     */
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

    /**
     * Finds aggregate IDs that have failed records.
     *
     * @return List of distinct aggregate IDs with failed records
     */
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

    /**
     * Finds all incomplete records for a specific aggregate ID.
     *
     * @param aggregateId The aggregate ID to search for
     * @return List of incomplete outbox records for the aggregate, ordered by creation time
     */
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

    /**
     * Counts the number of outbox records with the specified status.
     *
     * @param status The status to count records for
     * @return The number of records with the given status
     */
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

    /**
     * Deletes all records with the specified status.
     *
     * @param status The status of records to delete
     */
    @Transactional
    override fun deleteByStatus(status: OutboxRecordStatus) {
        val query = """
            delete from OutboxRecordEntity o
            where o.status = :status
        """

        entityManager
            .createQuery(query)
            .setParameter("status", status)
            .executeUpdate()
    }

    /**
     * Deletes records for a specific aggregate ID and status.
     *
     * @param aggregateId The aggregate ID
     * @param status The status of records to delete
     */
    @Transactional
    override fun deleteByAggregateIdAndStatus(
        aggregateId: String,
        status: OutboxRecordStatus,
    ) {
        val query = """
            delete from OutboxRecordEntity o
            where o.status = :status
            and o.aggregateId = :aggregateId
        """

        entityManager
            .createQuery(query)
            .setParameter("status", status)
            .setParameter("aggregateId", aggregateId)
            .executeUpdate()
    }
}
