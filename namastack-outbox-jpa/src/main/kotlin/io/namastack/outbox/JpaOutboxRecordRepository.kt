package io.namastack.outbox

import io.namastack.outbox.OutboxRecordEntityMapper.map
import jakarta.persistence.EntityManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.OffsetDateTime

/**
 * JPA implementation of the OutboxRecordRepository and OutboxRecordStatusRepository interfaces.
 *
 * This implementation uses JPA/Hibernate to persist and query outbox records
 * from a relational database.
 *
 * @param entityManager JPA entity manager for database operations
 * @param transactionTemplate Transaction template for programmatic transaction management
 * @param clock Clock for time-based operations
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
internal open class JpaOutboxRecordRepository(
    private val entityManager: EntityManager,
    private val transactionTemplate: TransactionTemplate,
    private val clock: Clock,
) : OutboxRecordRepository,
    OutboxRecordStatusRepository {
    /**
     * Saves an outbox record to the database.
     *
     * @param record The outbox record to save
     * @return The saved outbox record
     */
    override fun save(record: OutboxRecord): OutboxRecord =
        transactionTemplate.executeNonNull {
            val entity = map(record)

            val existingEntity = entityManager.find(OutboxRecordEntity::class.java, entity.id)

            if (existingEntity != null) {
                entityManager.merge(entity)
            } else {
                entityManager.persist(entity)
            }

            record
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
     * @param excludedAggregateIds Set of aggregate IDs to exclude
     * @param batchSize Maximum number of aggregate IDs to return
     * @return List of distinct aggregate IDs with pending records
     */
    override fun findAggregateIdsWithPendingRecords(
        status: OutboxRecordStatus,
        excludedAggregateIds: Set<String>,
        batchSize: Int,
    ): List<String> {
        val now = OffsetDateTime.now(clock)

        // Same fix: Only return aggregates where the record to be processed
        // is actually the NEXT record in sequence
        val query = """
            select o.aggregateId, min(o.createdAt) as minCreated
            from OutboxRecordEntity o
            where o.status = :status
            and o.nextRetryAt <= :now
            and o.aggregateId not in :excludedAggregateIds
            and not exists (
                select 1 from OutboxRecordEntity older
                where older.aggregateId = o.aggregateId
                and older.completedAt is null
                and older.createdAt < o.createdAt
            )
            group by o.aggregateId
            order by minCreated asc
        """

        return entityManager
            .createQuery(query)
            .setParameter("status", status)
            .setParameter("now", now)
            .setParameter("excludedAggregateIds", excludedAggregateIds)
            .setMaxResults(batchSize)
            .resultList
            .map { result ->
                if (result is Array<*>) {
                    result[0] as String
                } else {
                    result as String
                }
            }
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
    override fun deleteByStatus(status: OutboxRecordStatus) {
        transactionTemplate.executeNonNull {
            val query = """
                delete from OutboxRecordEntity o
                where o.status = :status
            """

            entityManager
                .createQuery(query)
                .setParameter("status", status)
                .executeUpdate()
        }
    }

    /**
     * Deletes records for a specific aggregate ID and status.
     *
     * @param aggregateId The aggregate ID
     * @param status The status of records to delete
     */
    override fun deleteByAggregateIdAndStatus(
        aggregateId: String,
        status: OutboxRecordStatus,
    ) {
        transactionTemplate.execute {
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

    override fun findAggregateIdsInPartitions(
        partitions: List<Int>,
        status: OutboxRecordStatus,
        batchSize: Int,
    ): List<String> {
        val now = OffsetDateTime.now(clock)

        // Critical fix: Only return aggregates where the NEW record to be processed
        // is actually the NEXT record in sequence (no older incomplete records exist)
        val query = """
            select o.aggregateId, min(o.createdAt) as minCreated
            from OutboxRecordEntity o
            where o.partition in :partitions
            and o.status = :status
            and o.nextRetryAt <= :now
            and not exists (
                select 1 from OutboxRecordEntity older
                where older.aggregateId = o.aggregateId
                and older.completedAt is null
                and older.createdAt < o.createdAt
            )
            group by o.aggregateId
            order by minCreated asc
        """

        return entityManager
            .createQuery(query)
            .setParameter("partitions", partitions)
            .setParameter("status", status)
            .setParameter("now", now)
            .setMaxResults(batchSize)
            .resultList
            .map { result ->
                if (result is Array<*>) {
                    result[0] as String
                } else {
                    result as String
                }
            }
    }

    override fun countRecordsByPartition(
        partition: Int,
        status: OutboxRecordStatus,
    ): Long {
        val query = """
            select count(o)
            from OutboxRecordEntity o
            where o.partition = :partition
            and o.status = :status
        """

        return entityManager
            .createQuery(query, Long::class.java)
            .setParameter("partition", partition)
            .setParameter("status", status)
            .singleResult
    }

    override fun findRecordsByPartition(
        partition: Int,
        status: OutboxRecordStatus?,
    ): List<OutboxRecord> {
        val queryBuilder =
            StringBuilder(
                """
            select o
            from OutboxRecordEntity o
            where o.partition = :partition
        """,
            )

        if (status != null) {
            queryBuilder.append(" and o.status = :status")
        }

        queryBuilder.append(" order by o.createdAt asc")

        val query =
            entityManager
                .createQuery(queryBuilder.toString(), OutboxRecordEntity::class.java)
                .setParameter("partition", partition)

        if (status != null) {
            query.setParameter("status", status)
        }

        return query.resultList.map { map(it) }
    }
}
