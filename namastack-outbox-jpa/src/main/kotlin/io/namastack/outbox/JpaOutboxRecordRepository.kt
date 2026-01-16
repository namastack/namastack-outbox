package io.namastack.outbox

import jakarta.persistence.EntityManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant

/**
 * JPA implementation of the OutboxRecordRepository and OutboxRecordStatusRepository interfaces.
 *
 * This implementation uses JPA/Hibernate to persist and query outbox records
 * from a relational database.
 *
 * @param entityManager JPA entity manager for database operations
 * @param transactionTemplate Transaction template for programmatic transaction management
 * @param entityMapper Mapper for converting between domain objects and JPA entities
 * @param clock Clock for time-based operations
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
internal open class JpaOutboxRecordRepository(
    private val entityManager: EntityManager,
    private val transactionTemplate: TransactionTemplate,
    private val entityMapper: OutboxRecordEntityMapper,
    private val clock: Clock,
) : OutboxRecordRepository,
    OutboxRecordStatusRepository {
    /**
     * Query to select record keys with no previous open/failed event (older.completedAt is null).
     * Used when ignoreRecordKeysWithPreviousFailure is true.
     */
    private val recordKeysQueryWithPreviousFailureFilter = """
        select o.recordKey, min(o.createdAt) as minCreated
        from OutboxRecordEntity o
        where o.partitionNo in :partitions
        and o.status = :status
        and o.nextRetryAt <= :now
        and not exists (
            select 1 from OutboxRecordEntity older
            where older.recordKey = o.recordKey
            and older.completedAt is null
            and older.createdAt < o.createdAt
        )
        group by o.recordKey
        order by minCreated asc
    """

    /**
     * Query to select all record keys with pending records, regardless of previous failures.
     * Used when ignoreRecordKeysWithPreviousFailure is false.
     */
    private val recordKeysQueryWithoutPreviousFailureFilter = """
        select o.recordKey, min(o.createdAt) as minCreated
        from OutboxRecordEntity o
        where o.partitionNo in :partitions
        and o.status = :status
        and o.nextRetryAt <= :now
        group by o.recordKey
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
     * Query to select all incomplete records for a specific record key ordered by creation time.
     */
    private val findIncompleteRecordsByRecordKeyQuery = """
        select o
        from OutboxRecordEntity o
        where o.recordKey = :recordKey
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
     * Query to delete records for a specific record key and status.
     */
    private val deleteByRecordKeyAndStatusQuery = """
        delete from OutboxRecordEntity o
        where o.status = :status
        and o.recordKey = :recordKey
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
    override fun <T> save(record: OutboxRecord<T>): OutboxRecord<T> =
        transactionTemplate.execute {
            val entity = entityMapper.map(record)
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
    override fun findPendingRecords(): List<OutboxRecord<*>> =
        entityManager
            .createQuery(findPendingRecordsQuery, OutboxRecordEntity::class.java)
            .setParameter("status", OutboxRecordStatus.NEW)
            .resultList
            .map { entityMapper.map(it) }

    /**
     * Finds all completed outbox records.
     *
     * @return List of completed outbox records ordered by creation time
     */
    override fun findCompletedRecords(): List<OutboxRecord<*>> =
        entityManager
            .createQuery(findCompletedRecordsQuery, OutboxRecordEntity::class.java)
            .setParameter("status", OutboxRecordStatus.COMPLETED)
            .resultList
            .map { entityMapper.map(it) }

    /**
     * Finds all failed outbox records.
     *
     * @return List of failed outbox records ordered by creation time
     */
    override fun findFailedRecords(): List<OutboxRecord<*>> =
        entityManager
            .createQuery(findFailedRecordsQuery, OutboxRecordEntity::class.java)
            .setParameter("status", OutboxRecordStatus.FAILED)
            .resultList
            .map { entityMapper.map(it) }

    /**
     * Finds all incomplete records for a specific record key.
     *
     * @param recordKey The record key to search for
     * @return List of pending outbox records for the record key, ordered by creation time
     */
    override fun findIncompleteRecordsByRecordKey(recordKey: String): List<OutboxRecord<*>> =
        entityManager
            .createQuery(findIncompleteRecordsByRecordKeyQuery, OutboxRecordEntity::class.java)
            .setParameter("recordKey", recordKey)
            .setParameter("status", OutboxRecordStatus.NEW)
            .resultList
            .map { entityMapper.map(it) }

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
        transactionTemplate.execute {
            entityManager
                .createQuery(deleteByStatusQuery)
                .setParameter("status", status)
                .executeUpdate()
        }
    }

    /**
     * Deletes records for a specific record key and status.
     *
     * @param recordKey The record key
     * @param status The status of records to delete
     */
    override fun deleteByRecordKeyAndStatus(
        recordKey: String,
        status: OutboxRecordStatus,
    ) {
        transactionTemplate.execute {
            entityManager
                .createQuery(deleteByRecordKeyAndStatusQuery)
                .setParameter("status", status)
                .setParameter("recordKey", recordKey)
                .executeUpdate()
        }
    }

    /**
     * Deletes a record by its unique ID.
     *
     * @param id The unique identifier of the outbox record
     */
    override fun deleteById(id: String) {
        transactionTemplate.executeWithoutResult {
            val entity = entityManager.find(OutboxRecordEntity::class.java, id)
            if (entity != null) {
                entityManager.remove(entity)
            }
        }
    }

    /**
     * Finds record keys in the given partitions with pending records.
     *
     * The query logic depends on the ignoreRecordKeysWithPreviousFailure flag:
     * - If true: only record keys with no previous open/failed event (older.completedAt is null) are returned.
     * - If false: all record keys with pending records are returned, regardless of previous failures.
     *
     * @param partitions List of partition numbers to search in
     * @param status The status to filter by
     * @param batchSize Maximum number of record keys to return
     * @param ignoreRecordKeysWithPreviousFailure Whether to exclude record keys with previous open/failed events
     * @return List of record keys with pending records in the specified partitions
     */
    override fun findRecordKeysInPartitions(
        partitions: Set<Int>,
        status: OutboxRecordStatus,
        batchSize: Int,
        ignoreRecordKeysWithPreviousFailure: Boolean,
    ): List<String> {
        val now = Instant.now(clock)
        val query =
            if (ignoreRecordKeysWithPreviousFailure) {
                recordKeysQueryWithPreviousFailureFilter
            } else {
                recordKeysQueryWithoutPreviousFailureFilter
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
