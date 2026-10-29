package io.namastack.outbox

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * JDBC implementation of the OutboxRecordRepository and OutboxRecordStatusRepository interfaces.
 *
 * This implementation uses Spring's JdbcClient to persist and query outbox records
 * from a relational database.
 *
 * @param jdbcClient JDBC client for database operations
 * @param transactionTemplate Transaction template for programmatic transaction management
 * @param entityMapper Mapper for converting between domain objects and entities
 * @param clock Clock for time-based operations
 * @param tableNameResolver Resolver for fully qualified table names
 *
 * @author Roland Beisel
 * @since 1.0.0
 */
internal open class JdbcOutboxRecordRepository(
    private val jdbcClient: JdbcClient,
    private val transactionTemplate: TransactionTemplate,
    private val entityMapper: JdbcOutboxRecordEntityMapper,
    private val clock: java.time.Clock,
    private val tableNameResolver: JdbcTableNameResolver,
    private val completionBatcher: PostgresOutboxCompletionBatcher? = null,
) : OutboxRecordRepository,
    OutboxRecordStatusRepository {
    private val tableName = tableNameResolver.outboxRecord
    private val rowMapper = JdbcOutboxRecordEntityRowMapper()

    /**
     * Query to update an existing outbox record.
     */
    private val updateRecordQuery =
        """
        UPDATE $tableName
        SET status = :status, record_key = :recordKey, record_type = :recordType, payload = :payload, context = :context,
            partition_no = :partitionNo, created_at = :createdAt, completed_at = :completedAt, failure_count = :failureCount,
            failure_reason = :failureReason, next_retry_at = :nextRetryAt, handler_id = :handlerId
        WHERE id = :id
        """.toSingleLine()

    /** Completion never needs to serialize or rewrite the delivery payload and routing fields. */
    private val updateCompletionQuery =
        """
        UPDATE $tableName
        SET status = :status, completed_at = :completedAt, failure_count = :failureCount,
            failure_reason = :failureReason, next_retry_at = :nextRetryAt
        WHERE id = :id
        """.toSingleLine()

    /**
     * Query to insert a new outbox record.
     */
    private val insertRecordQuery =
        """
        INSERT INTO $tableName
        (id, status, record_key, record_type, payload, context, partition_no,
         created_at, completed_at, failure_count, failure_reason, next_retry_at, handler_id)
        VALUES (:id, :status, :recordKey, :recordType, :payload, :context, :partitionNo,
         :createdAt, :completedAt, :failureCount, :failureReason, :nextRetryAt, :handlerId)
        """.toSingleLine()

    /**
     * Query to select outbox records by status ordered by creation time.
     */
    private val findByStatusQuery =
        """
        SELECT * FROM $tableName
        WHERE status = :status
        ORDER BY created_at, id
        """.toSingleLine()

    /**
     * Query to select outbox records by record key and status ordered by creation time.
     */
    private val findByKeyAndStatusQuery =
        """
        SELECT * FROM $tableName
        WHERE record_key = :recordKey AND status = :status
        ORDER BY created_at, id
        """.toSingleLine()

    /**
     * Query to select outbox records by status completed before a cutoff, oldest first.
     */
    private val findByStatusAndCompletedBeforeQuery =
        """
        SELECT * FROM $tableName
        WHERE status = :status AND completed_at < :cutoff
        ORDER BY completed_at
        """.toSingleLine()

    /**
     * Query to select outbox records by status with next retry time before a cutoff, oldest first.
     */
    private val findByStatusAndNextRetryBeforeQuery =
        """
        SELECT * FROM $tableName
        WHERE status = :status AND next_retry_at < :cutoff
        ORDER BY next_retry_at
        """.toSingleLine()

    /**
     * Query to select ids of outbox records by status completed before a cutoff, oldest first.
     */
    private val findIdsByStatusAndCompletedBeforeQuery =
        """
        SELECT id FROM $tableName
        WHERE status = :status AND completed_at < :cutoff
        ORDER BY completed_at
        """.toSingleLine()

    /**
     * Query to select ids of outbox records by status with next retry time before a cutoff, oldest first.
     */
    private val findIdsByStatusAndNextRetryBeforeQuery =
        """
        SELECT id FROM $tableName
        WHERE status = :status AND next_retry_at < :cutoff
        ORDER BY next_retry_at
        """.toSingleLine()

    /**
     * Query to select record keys with no previous open/failed event (older.completedAt is null).
     * Used when ignoreRecordKeysWithPreviousFailure is true.
     */
    private val recordKeysQueryWithPreviousFailureFilterTemplate =
        """
        SELECT o.record_key
        FROM $tableName o
        WHERE o.partition_no IN (:partitions)
          AND o.status = :status
          AND o.next_retry_at <= :now
          AND NOT EXISTS (
            SELECT 1 FROM $tableName older
            WHERE older.record_key = o.record_key
              AND older.completed_at IS NULL
              AND (
                older.created_at < o.created_at
                OR (older.created_at = o.created_at AND older.id < o.id)
              )
          )
        GROUP BY o.record_key
        ORDER BY MIN(o.created_at) ASC
        """.toSingleLine()

    /**
     * Query to select all record keys with pending records, regardless of previous failures.
     * Used when ignoreRecordKeysWithPreviousFailure is false.
     */
    private val recordKeysQueryWithoutPreviousFailureFilterTemplate =
        """
        SELECT o.record_key
        FROM $tableName o
        WHERE o.partition_no IN (:partitions)
          AND o.status = :status
          AND o.next_retry_at <= :now
        GROUP BY o.record_key
        ORDER BY MIN(o.created_at) ASC
        """.toSingleLine()

    /**
     * Query to count outbox records by status.
     */
    private val countByStatusQuery =
        """
        SELECT COUNT(*) FROM $tableName
        WHERE status = :status
        """.toSingleLine()

    /**
     * Query to count outbox records by partition and status.
     */
    private val countByPartitionStatusQuery =
        """
        SELECT COUNT(*) FROM $tableName
        WHERE partition_no = :partitionNo AND status = :status
        """.toSingleLine()

    /**
     * Query to count records across the specified partitions by status.
     */
    private val countByPartitionsStatusQuery =
        """
        SELECT COUNT(*) FROM $tableName
        WHERE partition_no IN (:partitions) AND status = :status
        """.toSingleLine()

    private val oldestCreatedAtByPartitionsStatusQuery =
        """
        SELECT MIN(created_at) FROM $tableName
        WHERE partition_no IN (:partitions) AND status = :status
        """.toSingleLine()

    private val oldestDueNextRetryAtQueryWithPreviousFailureFilter =
        """
        SELECT MIN(o.next_retry_at)
        FROM $tableName o
        WHERE o.partition_no IN (:partitions)
          AND o.status = :status
          AND o.next_retry_at <= :now
          AND NOT EXISTS (
            SELECT 1 FROM $tableName older
            WHERE older.record_key = o.record_key
              AND older.completed_at IS NULL
              AND (
                older.created_at < o.created_at
                OR (older.created_at = o.created_at AND older.id < o.id)
              )
          )
        """.toSingleLine()

    private val oldestDueNextRetryAtQueryWithoutPreviousFailureFilter =
        """
        SELECT MIN(o.next_retry_at)
        FROM $tableName o
        WHERE o.partition_no IN (:partitions)
          AND o.status = :status
          AND o.next_retry_at <= :now
        """.toSingleLine()

    /**
     * Query to delete outbox records by status.
     */
    private val deleteByStatusQuery =
        """
        DELETE FROM $tableName
        WHERE status = :status
        """.toSingleLine()

    /**
     * Query to delete outbox records by record key and status.
     */
    private val deleteByKeyAndStatusQuery =
        """
        DELETE FROM $tableName
        WHERE record_key = :recordKey AND status = :status
        """.toSingleLine()

    /**
     * Query to delete outbox record by id.
     */
    private val deleteByIdQuery =
        """
        DELETE FROM $tableName
        WHERE id = :id
        """.toSingleLine()

    /**
     * Query to delete outbox records by ids.
     */
    private val deleteByIdsQuery =
        """
        DELETE FROM $tableName
        WHERE id IN (:ids)
        """.toSingleLine()

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

    override fun updateCompletion(record: OutboxRecord<*>): Boolean {
        if (completionBatcher != null && !TransactionSynchronizationManager.isActualTransactionActive()) {
            return completionBatcher.complete(record)
        }
        return transactionTemplate.execute {
            jdbcClient
                .sql(updateCompletionQuery)
                .param("id", record.id)
                .param("status", record.status.name)
                .param("completedAt", record.completedAt?.let(Timestamp::from))
                .param("failureCount", record.failureCount)
                .param("failureReason", record.failureReason)
                .param("nextRetryAt", Timestamp.from(record.nextRetryAt))
                .update() == 1
        }
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
            .sql(findByStatusAndCompletedBeforeQuery)
            .param("status", OutboxRecordStatus.COMPLETED.name)
            .param("cutoff", Timestamp.from(completedBefore))
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
            .sql(findByStatusAndNextRetryBeforeQuery)
            .param("status", OutboxRecordStatus.FAILED.name)
            .param("cutoff", Timestamp.from(lastRetryBefore))
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
            .sql(findByKeyAndStatusQuery)
            .param("recordKey", recordKey)
            .param("status", OutboxRecordStatus.NEW.name)
            .query(rowMapper)
            .list()
            .filterNotNull()
            .map { entityMapper.map(it) }

    override fun findIncompleteRecordPages(
        recordKeys: Set<String>,
        maxRecordsPerKey: Int,
    ): Map<String, OutboxRecordPage> {
        require(recordKeys.size <= 1000 && maxRecordsPerKey >= 0)
        if (recordKeys.isEmpty()) return emptyMap()
        val sql =
            if (maxRecordsPerKey == 0) {
                "SELECT * FROM $tableName WHERE record_key IN (:keys) AND status = 'NEW' ORDER BY record_key, created_at, id"
            } else {
                """
            SELECT * FROM (
                SELECT r.*, ROW_NUMBER() OVER (PARTITION BY record_key ORDER BY created_at, id) AS key_row
                FROM $tableName r WHERE record_key IN (:keys) AND status = 'NEW'
            ) bounded WHERE key_row <= :maxRecords ORDER BY record_key, created_at, id
            """.toSingleLine()
            }
        val query = jdbcClient.sql(sql).param("keys", recordKeys.toList())
        if (maxRecordsPerKey > 0) query.param("maxRecords", maxRecordsPerKey)
        val grouped =
            query
                .query(rowMapper)
                .list()
                .filterNotNull()
                .groupBy { it.recordKey }
        return recordKeys.associateWith { key -> MappedRecordPage(grouped[key].orEmpty(), entityMapper) }
    }

    private class MappedRecordPage(
        private val rows: List<JdbcOutboxRecordEntity>,
        private val mapper: JdbcOutboxRecordEntityMapper,
    ) : AbstractList<OutboxRecord<*>>(),
        OutboxRecordPage {
        private val decoded = AtomicReferenceArray<OutboxRecord<*>?>(rows.size)
        override val size: Int get() = rows.size

        override fun nextRetryAt(index: Int): Instant = rows[index].nextRetryAt

        override fun recordId(index: Int): String = rows[index].id

        override fun get(index: Int): OutboxRecord<*> {
            decoded.get(index)?.let { return it }
            val record = mapper.map(rows[index])
            return if (decoded.compareAndSet(index, null, record)) record else checkNotNull(decoded.get(index))
        }
    }

    /**
     * Finds record keys in specified partitions that are ready for processing.
     * Optionally filters out keys with previous incomplete records.
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
                recordKeysQueryWithPreviousFailureFilterTemplate
            } else {
                recordKeysQueryWithoutPreviousFailureFilterTemplate
            }

        return jdbcClient
            .sql(query)
            .withMaxRows(batchSize)
            .param("partitions", partitions.toList())
            .param("status", status.name)
            .param("now", Timestamp.from(now))
            .query(String::class.java)
            .list()
            .filterNotNull()
    }

    /**
     * Counts outbox records by status.
     */
    override fun countByStatus(status: OutboxRecordStatus): Long =
        jdbcClient
            .sql(countByStatusQuery)
            .param("status", status.name)
            .query(Long::class.java)
            .single()

    /**
     * Counts outbox records by partition and status.
     */
    override fun countRecordsByPartition(
        partition: Int,
        status: OutboxRecordStatus,
    ): Long =
        jdbcClient
            .sql(countByPartitionStatusQuery)
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
            .sql(countByPartitionsStatusQuery)
            .param("partitions", partitions)
            .param("status", status.name)
            .query(Long::class.java)
            .single()
    }

    /**
     * Finds the earliest creation time of outbox records across the specified partitions by status.
     */
    override fun findOldestCreatedAtByPartitions(
        partitions: Set<Int>,
        status: OutboxRecordStatus,
    ): Instant? {
        if (partitions.isEmpty()) return null

        return jdbcClient
            .sql(oldestCreatedAtByPartitionsStatusQuery)
            .param("partitions", partitions)
            .param("status", status.name)
            .query { rs, _ -> rs.getTimestamp(1)?.toInstant() }
            .list()
            .firstOrNull()
    }

    /**
     * Finds the earliest next retry time of due outbox records across the specified partitions.
     */
    override fun findOldestDueNextRetryAtByPartitions(
        partitions: Set<Int>,
        status: OutboxRecordStatus,
        now: Instant,
        ignoreRecordKeysWithPreviousFailure: Boolean,
    ): Instant? {
        if (partitions.isEmpty()) return null

        val query =
            if (ignoreRecordKeysWithPreviousFailure) {
                oldestDueNextRetryAtQueryWithPreviousFailureFilter
            } else {
                oldestDueNextRetryAtQueryWithoutPreviousFailureFilter
            }

        return jdbcClient
            .sql(query)
            .param("partitions", partitions)
            .param("status", status.name)
            .param("now", Timestamp.from(now))
            .query { rs, _ -> rs.getTimestamp(1)?.toInstant() }
            .list()
            .firstOrNull()
    }

    /**
     * Deletes all outbox records with the specified status.
     */
    override fun deleteByStatus(status: OutboxRecordStatus) {
        transactionTemplate.execute {
            jdbcClient
                .sql(deleteByStatusQuery)
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
                .sql(deleteByKeyAndStatusQuery)
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
                .sql(deleteByIdQuery)
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
                .sql(deleteByIdsQuery)
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
                    .sql(findIdsByStatusAndCompletedBeforeQuery)
                    .param("status", OutboxRecordStatus.COMPLETED.name)
                    .param("cutoff", Timestamp.from(completedBefore))
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
                    .sql(findIdsByStatusAndNextRetryBeforeQuery)
                    .param("status", OutboxRecordStatus.FAILED.name)
                    .param("cutoff", Timestamp.from(lastRetryBefore))
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
            .sql(updateRecordQuery)
            .param("status", entity.status.name)
            .param("recordKey", entity.recordKey)
            .param("recordType", entity.recordType)
            .param("payload", entity.payload)
            .param("context", entity.context)
            .param("partitionNo", entity.partitionNo)
            .param("createdAt", Timestamp.from(entity.createdAt))
            .param("completedAt", entity.completedAt?.let { Timestamp.from(it) })
            .param("failureCount", entity.failureCount)
            .param("failureReason", entity.failureReason)
            .param("nextRetryAt", Timestamp.from(entity.nextRetryAt))
            .param("handlerId", entity.handlerId)
            .param("id", entity.id)
            .update()

    /**
     * Inserts a new record into the database.
     */
    private fun insert(entity: JdbcOutboxRecordEntity) {
        jdbcClient
            .sql(insertRecordQuery)
            .param("id", entity.id)
            .param("status", entity.status.name)
            .param("recordKey", entity.recordKey)
            .param("recordType", entity.recordType)
            .param("payload", entity.payload)
            .param("context", entity.context)
            .param("partitionNo", entity.partitionNo)
            .param("createdAt", Timestamp.from(entity.createdAt))
            .param("completedAt", entity.completedAt?.let { Timestamp.from(it) })
            .param("failureCount", entity.failureCount)
            .param("failureReason", entity.failureReason)
            .param("nextRetryAt", Timestamp.from(entity.nextRetryAt))
            .param("handlerId", entity.handlerId)
            .update()
    }

    /**
     * Finds all records with the specified status.
     */
    private fun findRecordsByStatus(status: OutboxRecordStatus): List<OutboxRecord<*>> =
        jdbcClient
            .sql(findByStatusQuery)
            .param("status", status.name)
            .query(rowMapper)
            .list()
            .filterNotNull()
            .map { entityMapper.map(it) }
}
