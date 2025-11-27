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
     * Query to select aggregate IDs with no previous open/failed event (older.completedAt is null).
     * Used when ignoreAggregatesWithPreviousFailure is true.
     */
    private val aggregateIdsQueryWithPreviousFailureFilter = """
        select o.aggregateId, min(o.createdAt) as minCreated
        from OutboxRecordEntity o
        where o.partitionNo in :partitions
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

    /**
     * Query to select all aggregate IDs with pending records, regardless of previous failures.
     * Used when ignoreAggregatesWithPreviousFailure is false.
     */
    private val aggregateIdsQueryWithoutPreviousFailureFilter = """
        select o.aggregateId, min(o.createdAt) as minCreated
        from OutboxRecordEntity o
        where o.partitionNo in :partitions
        and o.status = :status
        and o.nextRetryAt <= :now
        group by o.aggregateId
        order by minCreated asc
    """

    /**
     * Query to select all pending outbox records ordered by creation time.
     */
    private val findPendingRecordsQuery = """
        select o
        from OutboxRecordEntity o
        where o.status = :status
        order by o.createdAt asc
    """

    /**
     * Query to select all completed outbox records ordered by creation time.
     */
    private val findCompletedRecordsQuery = """
        select o
        from OutboxRecordEntity o
        where o.status = :status
        order by o.createdAt asc
    """

    /**
     * Query to select all failed outbox records ordered by creation time.
     */
    private val findFailedRecordsQuery = """
        select o
        from OutboxRecordEntity o
        where o.status = :status
        order by o.createdAt asc
    """

    /**
     * Query to select all incomplete records for a specific aggregate ID ordered by creation time.
     */
    private val findIncompleteRecordsByAggregateIdQuery = """
        select o
        from OutboxRecordEntity o
        where o.aggregateId = :aggregateId
        and o.status = :status
        order by o.createdAt asc
    """

    /**
     * Query to count the number of outbox records with the specified status.
     */
    private val countByStatusQuery = """
        select count(o)
        from OutboxRecordEntity o
        where o.status = :status
    """

    /**
     * Query to delete all records with the specified status.
     */
    private val deleteByStatusQuery = """
        delete from OutboxRecordEntity o
        where o.status = :status
    """

    /**
     * Query to delete records for a specific aggregate ID and status.
     */
    private val deleteByAggregateIdAndStatusQuery = """
        delete from OutboxRecordEntity o
        where o.status = :status
        and o.aggregateId = :aggregateId
    """

    /**
     * Query to count records in a specific partition by status.
     */
    private val countRecordsByPartitionQuery = """
        select count(o)
        from OutboxRecordEntity o
        where o.partitionNo = :partition
        and o.status = :status
    """

    /**
     * Saves an outbox record to the database.
     *
     * If a record with the same ID already exists, it is updated (merged).
     * Otherwise, a new record is inserted.
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
    override fun findPendingRecords(): List<OutboxRecord> =
        entityManager
            .createQuery(findPendingRecordsQuery, OutboxRecordEntity::class.java)
            .setParameter("status", OutboxRecordStatus.NEW)
            .resultList
            .map { map(it) }

    /**
     * Finds all completed outbox records.
     *
     * @return List of completed outbox records ordered by creation time
     */
    override fun findCompletedRecords(): List<OutboxRecord> =
        entityManager
            .createQuery(findCompletedRecordsQuery, OutboxRecordEntity::class.java)
            .setParameter("status", OutboxRecordStatus.COMPLETED)
            .resultList
            .map { map(it) }

    /**
     * Finds all failed outbox records.
     *
     * @return List of failed outbox records ordered by creation time
     */
    override fun findFailedRecords(): List<OutboxRecord> =
        entityManager
            .createQuery(findFailedRecordsQuery, OutboxRecordEntity::class.java)
            .setParameter("status", OutboxRecordStatus.FAILED)
            .resultList
            .map { map(it) }

    /**
     * Finds all incomplete records for a specific aggregate ID.
     *
     * @param aggregateId The aggregate ID to search for
     * @return List of pending outbox records for the aggregate, ordered by creation time
     */
    override fun findIncompleteRecordsByAggregateId(aggregateId: String): List<OutboxRecord> =
        entityManager
            .createQuery(findIncompleteRecordsByAggregateIdQuery, OutboxRecordEntity::class.java)
            .setParameter("aggregateId", aggregateId)
            .setParameter("status", OutboxRecordStatus.NEW)
            .resultList
            .map { map(it) }

    /**
     * Counts the number of outbox records with the specified status.
     *
     * @param status The status to count records for
     * @return The number of records with the given status
     */
    override fun countByStatus(status: OutboxRecordStatus): Long =
        entityManager
            .createQuery(countByStatusQuery, Long::class.java)
            .setParameter("status", status)
            .singleResult

    /**
     * Counts records in a specific partition by status.
     *
     * @param partition The partition number
     * @param status The status to count
     * @return Number of records in the partition with the specified status
     */
    override fun countRecordsByPartition(
        partition: Int,
        status: OutboxRecordStatus,
    ): Long =
        entityManager
            .createQuery(countRecordsByPartitionQuery, Long::class.java)
            .setParameter("partition", partition)
            .setParameter("status", status)
            .singleResult

    /**
     * Deletes all records with the specified status.
     *
     * @param status The status of records to delete
     */
    override fun deleteByStatus(status: OutboxRecordStatus) {
        transactionTemplate.executeNonNull {
            entityManager
                .createQuery(deleteByStatusQuery)
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
        transactionTemplate.executeNonNull {
            entityManager
                .createQuery(deleteByAggregateIdAndStatusQuery)
                .setParameter("status", status)
                .setParameter("aggregateId", aggregateId)
                .executeUpdate()
        }
    }

    /**
     * Deletes a record by its unique ID.
     *
     * @param id The unique identifier of the outbox record
     */
    override fun deleteById(id: String) {
        transactionTemplate.executeNonNull {
            val entity = entityManager.find(OutboxRecordEntity::class.java, id) ?: return@executeNonNull
            entityManager.remove(entity)
        }
    }

    /**
     * Finds aggregate IDs in the given partitions with pending records.
     *
     * The query logic depends on the ignoreAggregatesWithPreviousFailure flag:
     * - If true: only aggregate IDs with no previous open/failed event (older.completedAt is null) are returned.
     * - If false: all aggregate IDs with pending records are returned, regardless of previous failures.
     *
     * @param partitions List of partition numbers to search in
     * @param status The status to filter by
     * @param batchSize Maximum number of aggregate IDs to return
     * @param ignoreAggregatesWithPreviousFailure Whether to exclude aggregates with previous open/failed events
     * @return List of aggregate IDs with pending records in the specified partitions
     */
    override fun findAggregateIdsInPartitions(
        partitions: Set<Int>,
        status: OutboxRecordStatus,
        batchSize: Int,
        ignoreAggregatesWithPreviousFailure: Boolean,
    ): List<String> {
        val now = OffsetDateTime.now(clock)
        val query =
            if (ignoreAggregatesWithPreviousFailure) {
                aggregateIdsQueryWithPreviousFailureFilter
            } else {
                aggregateIdsQueryWithoutPreviousFailureFilter
            }

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
}
