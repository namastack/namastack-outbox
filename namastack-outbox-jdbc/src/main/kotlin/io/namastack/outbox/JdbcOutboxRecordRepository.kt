package io.namastack.outbox

import io.namastack.outbox.OutboxRecordRepository.Companion.MAX_RECORD_KEYS_PER_FETCH
import io.namastack.outbox.query.JdbcOutboxRecordQueries
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/**
 * JDBC implementation of [OutboxRecordRepository].
 *
 * This implementation uses Spring's JdbcClient to persist and query outbox records
 * from a relational database.
 *
 * @param jdbcClient JDBC client for database operations
 * @param transactionTemplate Transaction template for programmatic transaction management
 * @param entityMapper Mapper for converting between domain objects and entities
 * @param clock Clock for time-based operations
 * @param queries Supplies the SQL statements on first use, so construction opens no connection
 *
 * @author Roland Beisel
 * @since 1.0.0
 */
internal open class JdbcOutboxRecordRepository(
    private val jdbcClient: JdbcClient,
    private val transactionTemplate: TransactionTemplate,
    private val entityMapper: JdbcOutboxRecordEntityMapper,
    private val clock: java.time.Clock,
    queries: () -> JdbcOutboxRecordQueries,
) : OutboxRecordRepository {
    private val rowMapper = JdbcOutboxRecordEntityRowMapper()
    private val sql by lazy(queries)

    /**
     * Saves an outbox record.
     * Updates existing record or inserts new one.
     */
    override fun <T> save(record: OutboxRecord<T>): OutboxRecord<T> =
        transactionTemplate.execute {
            val entity = entityMapper.map(record)

            val updated = tryUpdate(entity)

            if (updated == 0) {
                insert(entity)
            }

            record
        }

    /**
     * Creates a new outbox record.
     *
     * @param record The outbox record to create
     * @return The created outbox record
     */
    override fun <T> create(record: OutboxRecord<T>): OutboxRecord<T> =
        transactionTemplate.execute {
            val entity = entityMapper.map(record)
            insert(entity)
            record
        }

    /**
     * Updates an existing outbox record. Does nothing if the record no longer exists.
     *
     * @param record The outbox record to update
     * @return True if the record was updated, false if it no longer exists
     */
    override fun <T> update(record: OutboxRecord<T>): Boolean =
        transactionTemplate.execute {
            val entity = entityMapper.map(record)
            val updatedRows = tryUpdate(entity)
            updatedRows == 1
        }

    /**
     * Updates only the processing state of an existing record. Does nothing if the record no longer exists.
     * The payload and context are neither serialized nor written.
     *
     * @param record The outbox record whose processing state changed
     * @return True if the record was updated, false if it no longer exists
     */
    override fun <T> updateProcessingState(record: OutboxRecord<T>): Boolean =
        transactionTemplate.execute {
            val updatedRows =
                jdbcClient
                    .sql(sql.updateProcessingState)
                    .param("status", record.status.name)
                    .param("completedAt", record.completedAt?.toTimestamp())
                    .param("failureCount", record.failureCount)
                    .param("failureReason", record.failureReason)
                    .param("nextRetryAt", record.nextRetryAt.toTimestamp())
                    .param("id", record.id)
                    .update()
            updatedRows == 1
        }

    /**
     * Finds all pending outbox records ordered by creation time.
     */
    override fun findPendingRecords(): List<OutboxRecord<*>> = findRecordsByStatus(OutboxRecordStatus.NEW)

    /**
     * Finds all completed outbox records ordered by creation time.
     */
    override fun findCompletedRecords(): List<OutboxRecord<*>> = findRecordsByStatus(OutboxRecordStatus.COMPLETED)

    /**
     * Finds at most [limit] completed records completed before the cutoff, oldest first.
     */
    override fun findCompletedRecords(
        completedBefore: Instant,
        limit: Int,
    ): List<OutboxRecord<*>> {
        require(limit > 0) { "limit must be positive but was $limit" }

        return jdbcClient
            .sql(sql.findByStatusAndCompletedBefore)
            .param("status", OutboxRecordStatus.COMPLETED.name)
            .param("cutoff", completedBefore.toTimestamp())
            .param("limit", limit)
            .withMaxRows(limit)
            .query(rowMapper)
            .list()
            .filterNotNull()
            .map { entityMapper.map(it) }
    }

    /**
     * Finds all failed outbox records ordered by creation time.
     */
    override fun findFailedRecords(): List<OutboxRecord<*>> = findRecordsByStatus(OutboxRecordStatus.FAILED)

    /**
     * Finds at most [limit] failed records whose last scheduled attempt is before the cutoff, oldest first.
     */
    override fun findFailedRecords(
        lastRetryBefore: Instant,
        limit: Int,
    ): List<OutboxRecord<*>> {
        require(limit > 0) { "limit must be positive but was $limit" }

        return jdbcClient
            .sql(sql.findByStatusAndNextRetryBefore)
            .param("status", OutboxRecordStatus.FAILED.name)
            .param("cutoff", lastRetryBefore.toTimestamp())
            .param("limit", limit)
            .withMaxRows(limit)
            .query(rowMapper)
            .list()
            .filterNotNull()
            .map { entityMapper.map(it) }
    }

    /**
     * Finds all incomplete records for a specific record key ordered by creation time.
     */
    override fun findIncompleteRecordsByRecordKey(recordKey: String): List<OutboxRecord<*>> =
        jdbcClient
            .sql(sql.findIncompleteByKeyAndStatus)
            .param("recordKey", recordKey)
            .param("status", OutboxRecordStatus.NEW.name)
            .query(rowMapper)
            .list()
            .filterNotNull()
            .map { entityMapper.map(it) }

    /**
     * Loads raw NEW records for selected storage keys from the resolved table.
     *
     * Payload and context decoding is deferred until a worker accesses an eligible element.
     * The same predicate is used for strict and non-strict processing; retry eligibility and
     * failure handling remain the worker's responsibility. Missing keys receive empty pages.
     *
     * Records are loaded in one bulk read and grouped by their exact record key.
     *
     * @param recordKeys Selected storage keys, at most [MAX_RECORD_KEYS_PER_FETCH] per call
     * @return An ordered, lazily decoded page for every requested key
     * @throws IllegalArgumentException if the portable key limit is exceeded
     */
    override fun findIncompleteRecordPages(recordKeys: Set<String>): Map<String, OutboxRecordPage> {
        require(recordKeys.size <= MAX_RECORD_KEYS_PER_FETCH) {
            "A record fetch can contain at most $MAX_RECORD_KEYS_PER_FETCH keys"
        }

        if (recordKeys.isEmpty()) return emptyMap()

        val rowsByKey =
            jdbcClient
                .sql(sql.findIncompleteByKeysAndStatus)
                .param("recordKeys", recordKeys)
                .param("status", OutboxRecordStatus.NEW.name)
                .query(rowMapper)
                .list()
                .filterNotNull()
                .groupBy { it.recordKey }

        return recordKeys.associateWith { key -> LazyOutboxRecordPage(rowsByKey[key].orEmpty(), entityMapper::map) }
    }

    /**
     * Finds record keys in specified partitions with due `NEW` records.
     * Optionally filters out keys with previous incomplete records.
     */
    override fun findRecordKeysInPartitions(
        partitions: Set<Int>,
        batchSize: Int,
        ignoreRecordKeysWithPreviousFailure: Boolean,
    ): List<String> {
        val now = Instant.now(clock)

        val query =
            if (ignoreRecordKeysWithPreviousFailure) {
                sql.findStrictRecordKeys
            } else {
                sql.findNonStrictRecordKeys
            }

        return jdbcClient
            .sql(query)
            .param("partitions", partitions.toList())
            .param("status", OutboxRecordStatus.NEW.name)
            .param("now", now.toTimestamp())
            .param("limit", batchSize)
            .withMaxRows(batchSize)
            .query(String::class.java)
            .list()
            .filterNotNull()
    }

    /**
     * Counts outbox records by status.
     */
    override fun countByStatus(status: OutboxRecordStatus): Long =
        jdbcClient
            .sql(sql.countByStatus)
            .param("status", status.name)
            .query(Long::class.java)
            .single()

    /**
     * Counts outbox records grouped by status in a single query.
     *
     * Returns an entry for every requested status, with zero when no record has that status.
     * Returns an empty map without querying the data store when [statuses] is empty.
     *
     * @param statuses The statuses to count
     * @return Number of records per requested status
     */
    override fun countByStatuses(statuses: Set<OutboxRecordStatus>): Map<OutboxRecordStatus, Long> {
        if (statuses.isEmpty()) return emptyMap()

        val counts =
            jdbcClient
                .sql(sql.countByStatuses)
                .param("statuses", statuses.map { it.name })
                .query { rs, _ -> OutboxRecordStatus.valueOf(rs.getString(1)) to rs.getLong(2) }
                .list()
                .toMap()

        return statuses.associateWith { counts[it] ?: 0L }
    }

    /**
     * Counts outbox records by partition and status.
     */
    override fun countRecordsByPartition(
        partition: Int,
        status: OutboxRecordStatus,
    ): Long =
        jdbcClient
            .sql(sql.countByPartitionStatus)
            .param("partitionNo", partition)
            .param("status", status.name)
            .query(Long::class.java)
            .single()

    /**
     * Counts outbox records across the specified partitions by status in a single query.
     *
     * Includes records awaiting a future retry. Returns zero without querying the data store
     * when [partitions] is empty.
     *
     * @param partitions The partition numbers to count
     * @param status The status to count
     * @return Total number of matching records, or zero if the set is empty or no records match
     */
    override fun countRecordsByPartitions(
        partitions: Set<Int>,
        status: OutboxRecordStatus,
    ): Long {
        if (partitions.isEmpty()) return 0L

        return jdbcClient
            .sql(sql.countByPartitionsStatus)
            .param("partitions", partitions)
            .param("status", status.name)
            .query(Long::class.java)
            .single()
    }

    /**
     * Finds the earliest creation time of `NEW` records across the specified partitions.
     */
    override fun findOldestCreatedAtByPartitions(partitions: Set<Int>): Instant? {
        if (partitions.isEmpty()) return null

        return jdbcClient
            .sql(sql.oldestCreatedAtByPartitionsStatus)
            .param("partitions", partitions)
            .param("status", OutboxRecordStatus.NEW.name)
            .param("limit", 1)
            .withMaxRows(1)
            .query { rs, _ -> rs.getInstantOrNull(1) }
            .list()
            .firstOrNull()
    }

    /**
     * Finds the earliest next retry time of due `NEW` records across the specified partitions.
     */
    override fun findOldestDueNextRetryAtByPartitions(
        partitions: Set<Int>,
        now: Instant,
        ignoreRecordKeysWithPreviousFailure: Boolean,
    ): Instant? {
        if (partitions.isEmpty()) return null

        val query =
            if (ignoreRecordKeysWithPreviousFailure) {
                sql.oldestDueStrict
            } else {
                sql.oldestDueNonStrict
            }

        return jdbcClient
            .sql(query)
            .param("partitions", partitions)
            .param("status", OutboxRecordStatus.NEW.name)
            .param("now", now.toTimestamp())
            .param("limit", 1)
            .withMaxRows(1)
            .query { rs, _ -> rs.getInstantOrNull(1) }
            .list()
            .firstOrNull()
    }

    /**
     * Deletes all outbox records with the specified status.
     */
    override fun deleteByStatus(status: OutboxRecordStatus) {
        transactionTemplate.execute {
            jdbcClient
                .sql(sql.deleteByStatus)
                .param("status", status.name)
                .update()
        }
    }

    /**
     * Deletes outbox records by record key and status.
     */
    override fun deleteByRecordKeyAndStatus(
        recordKey: String,
        status: OutboxRecordStatus,
    ) {
        transactionTemplate.execute {
            jdbcClient
                .sql(sql.deleteByKeyAndStatus)
                .param("recordKey", recordKey)
                .param("status", status.name)
                .update()
        }
    }

    /**
     * Deletes a single outbox record by ID.
     */
    override fun deleteById(id: String) {
        transactionTemplate.executeWithoutResult {
            jdbcClient
                .sql(sql.deleteById)
                .param("id", id)
                .update()
        }
    }

    /**
     * Deletes outbox records by ids.
     */
    override fun deleteByIds(ids: Collection<String>): Int {
        if (ids.isEmpty()) return 0

        return transactionTemplate.execute {
            jdbcClient
                .sql(sql.deleteByIds)
                .param("ids", ids)
                .update()
        } ?: 0
    }

    /**
     * Deletes at most [limit] completed records completed before the cutoff, oldest first.
     */
    override fun deleteCompletedRecords(
        completedBefore: Instant,
        limit: Int,
    ): Int {
        require(limit > 0) { "limit must be positive but was $limit" }

        return transactionTemplate.execute {
            val ids =
                jdbcClient
                    .sql(sql.findIdsByStatusAndCompletedBefore)
                    .param("status", OutboxRecordStatus.COMPLETED.name)
                    .param("cutoff", completedBefore.toTimestamp())
                    .param("limit", limit)
                    .withMaxRows(limit)
                    .query(String::class.java)
                    .list()
                    .filterNotNull()

            deleteByIds(ids)
        } ?: 0
    }

    /**
     * Deletes at most [limit] failed records whose last scheduled attempt is before the cutoff, oldest first.
     */
    override fun deleteFailedRecords(
        lastRetryBefore: Instant,
        limit: Int,
    ): Int {
        require(limit > 0) { "limit must be positive but was $limit" }

        return transactionTemplate.execute {
            val ids =
                jdbcClient
                    .sql(sql.findIdsByStatusAndNextRetryBefore)
                    .param("status", OutboxRecordStatus.FAILED.name)
                    .param("cutoff", lastRetryBefore.toTimestamp())
                    .param("limit", limit)
                    .withMaxRows(limit)
                    .query(String::class.java)
                    .list()
                    .filterNotNull()

            deleteByIds(ids)
        } ?: 0
    }

    /**
     * Attempts to update an existing record. Returns number of rows updated.
     */
    private fun tryUpdate(entity: JdbcOutboxRecordEntity): Int =
        jdbcClient
            .sql(sql.updateRecord)
            .param("status", entity.status.name)
            .param("recordKey", entity.recordKey)
            .param("recordType", entity.recordType)
            .param("payload", entity.payload)
            .param("context", entity.context)
            .param("partitionNo", entity.partitionNo)
            .param("createdAt", entity.createdAt.toTimestamp())
            .param("completedAt", entity.completedAt?.toTimestamp())
            .param("failureCount", entity.failureCount)
            .param("failureReason", entity.failureReason)
            .param("nextRetryAt", entity.nextRetryAt.toTimestamp())
            .param("handlerId", entity.handlerId)
            .param("id", entity.id)
            .update()

    /**
     * Inserts a new record into the database.
     */
    private fun insert(entity: JdbcOutboxRecordEntity) {
        jdbcClient
            .sql(sql.insertRecord)
            .param("id", entity.id)
            .param("status", entity.status.name)
            .param("recordKey", entity.recordKey)
            .param("recordType", entity.recordType)
            .param("payload", entity.payload)
            .param("context", entity.context)
            .param("partitionNo", entity.partitionNo)
            .param("createdAt", entity.createdAt.toTimestamp())
            .param("completedAt", entity.completedAt?.toTimestamp())
            .param("failureCount", entity.failureCount)
            .param("failureReason", entity.failureReason)
            .param("nextRetryAt", entity.nextRetryAt.toTimestamp())
            .param("handlerId", entity.handlerId)
            .update()
    }

    /**
     * Finds all records with the specified status.
     */
    private fun findRecordsByStatus(status: OutboxRecordStatus): List<OutboxRecord<*>> =
        jdbcClient
            .sql(sql.findByStatus)
            .param("status", status.name)
            .query(rowMapper)
            .list()
            .filterNotNull()
            .map { entityMapper.map(it) }
}
