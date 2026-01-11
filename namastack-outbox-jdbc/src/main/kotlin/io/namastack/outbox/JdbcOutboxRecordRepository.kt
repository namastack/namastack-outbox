package io.namastack.outbox

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime

/**
 * JDBC implementation of the OutboxRecordRepository and OutboxRecordStatusRepository interfaces.
 *
 * This implementation uses Spring's JdbcClient to persist and query outbox records
 * from a relational database.
 *
 * @param jdbcClient JDBC client for database operations
 * @param transactionTemplate Transaction template for programmatic transaction management
 * @param entityMapper Mapper for converting between domain objects and JPA entities
 * @param clock Clock for time-based operations
 *
 * @author Roland Beisel
 * @since 1.1.0
 */
internal open class JdbcOutboxRecordRepository(
    private val jdbcClient: JdbcClient,
    private val transactionTemplate: TransactionTemplate,
    private val entityMapper: JdbcOutboxRecordEntityMapper,
    private val clock: java.time.Clock,
) : OutboxRecordRepository,
    OutboxRecordStatusRepository {
    /**
     * Query to update an existing outbox record.
     */
    private val updateRecordQuery = """
        UPDATE outbox_record
        SET status = ?, record_key = ?, record_type = ?, payload = ?, context = ?,
            partition_no = ?, created_at = ?, completed_at = ?, failure_count = ?,
            failure_reason = ?, next_retry_at = ?, handler_id = ?
        WHERE id = ?
    """

    /**
     * Query to insert a new outbox record.
     */
    private val insertRecordQuery = """
        INSERT INTO outbox_record
        (id, status, record_key, record_type, payload, context, partition_no,
         created_at, completed_at, failure_count, failure_reason, next_retry_at, handler_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """

    /**
     * Query to select outbox records by status ordered by creation time.
     */
    private val findByStatusQuery = """
        SELECT * FROM outbox_record
        WHERE status = ?
        ORDER BY created_at
    """

    /**
     * Query to select outbox records by record key and status ordered by creation time.
     */
    private val findByKeyAndStatusQuery = """
        SELECT * FROM outbox_record
        WHERE record_key = ? AND status = ?
        ORDER BY created_at
    """

    /**
     * Query to count outbox records by status.
     */
    private val countByStatusQuery = """
        SELECT COUNT(*) FROM outbox_record
        WHERE status = ?
    """

    /**
     * Query to count outbox records by partition and status.
     */
    private val countByPartitionStatusQuery = """
        SELECT COUNT(*) FROM outbox_record
        WHERE partition_no = ? AND status = ?
    """

    /**
     * Query to delete outbox records by status.
     */
    private val deleteByStatusQuery = """
        DELETE FROM outbox_record
        WHERE status = ?
    """

    /**
     * Query to delete outbox records by record key and status.
     */
    private val deleteByKeyAndStatusQuery = """
        DELETE FROM outbox_record
        WHERE record_key = ? AND status = ?
    """

    /**
     * Query to delete outbox record by id.
     */
    private val deleteByIdQuery = """
        DELETE FROM outbox_record
        WHERE id = ?
    """

    /**
     * Query to select record keys with no previous open/failed event (older.completedAt is null).
     * Used when ignoreRecordKeysWithPreviousFailure is true.
     * Note: Partition placeholders are dynamically injected.
     */
    private val recordKeysQueryWithPreviousFailureFilterTemplate = """
        SELECT o.record_key
        FROM outbox_record o
        WHERE o.partition_no IN (%s)
          AND o.status = ?
          AND o.next_retry_at <= ?
          AND NOT EXISTS (
            SELECT 1 FROM outbox_record older
            WHERE older.record_key = o.record_key
              AND older.completed_at IS NULL
              AND older.created_at < o.created_at
          )
        GROUP BY o.record_key
        ORDER BY MIN(o.created_at) ASC
        LIMIT ?
    """

    /**
     * Query to select all record keys with pending records, regardless of previous failures.
     * Used when ignoreRecordKeysWithPreviousFailure is false.
     * Note: Partition placeholders are dynamically injected.
     */
    private val recordKeysQueryWithoutPreviousFailureFilterTemplate = """
        SELECT o.record_key
        FROM outbox_record o
        WHERE o.partition_no IN (%s)
          AND o.status = ?
          AND o.next_retry_at <= ?
        GROUP BY o.record_key
        ORDER BY MIN(o.created_at) ASC
        LIMIT ?
    """

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
     * Finds all pending outbox records ordered by creation time.
     */
    override fun findPendingRecords(): List<OutboxRecord<*>> = findRecordsByStatus(OutboxRecordStatus.NEW)

    /**
     * Finds all completed outbox records ordered by creation time.
     */
    override fun findCompletedRecords(): List<OutboxRecord<*>> = findRecordsByStatus(OutboxRecordStatus.COMPLETED)

    /**
     * Finds all failed outbox records ordered by creation time.
     */
    override fun findFailedRecords(): List<OutboxRecord<*>> = findRecordsByStatus(OutboxRecordStatus.FAILED)

    /**
     * Finds all incomplete records for a specific record key ordered by creation time.
     */
    override fun findIncompleteRecordsByRecordKey(recordKey: String): List<OutboxRecord<*>> =
        jdbcClient
            .sql(findByKeyAndStatusQuery)
            .param(recordKey)
            .param(OutboxRecordStatus.NEW.name)
            .query(JdbcOutboxRecordEntity::class.java)
            .list()
            .filterNotNull()
            .map { entityMapper.map(it) }

    /**
     * Counts outbox records by status.
     */
    override fun countByStatus(status: OutboxRecordStatus): Long =
        jdbcClient
            .sql(countByStatusQuery)
            .param(status.name)
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
            .param(partition)
            .param(status.name)
            .query(Long::class.java)
            .single()

    /**
     * Deletes all outbox records with the specified status.
     */
    override fun deleteByStatus(status: OutboxRecordStatus) {
        transactionTemplate.execute {
            jdbcClient
                .sql(deleteByStatusQuery.trimIndent())
                .param(status.name)
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
                .sql(deleteByKeyAndStatusQuery.trimIndent())
                .param(recordKey)
                .param(status.name)
                .update()
        }
    }

    /**
     * Deletes a single outbox record by ID.
     */
    override fun deleteById(id: String) {
        transactionTemplate.executeWithoutResult {
            jdbcClient
                .sql(deleteByIdQuery.trimIndent())
                .param(id)
                .update()
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
        val now = OffsetDateTime.now(clock)
        val query = buildRecordKeysQuery(partitions, ignoreRecordKeysWithPreviousFailure)

        val clientBuilder = jdbcClient.sql(query)
        partitions.forEach { clientBuilder.param(it) }
        clientBuilder.param(status.name)
        clientBuilder.param(now)
        clientBuilder.param(batchSize)

        return clientBuilder.query(String::class.java).list().filterNotNull()
    }

    /**
     * Attempts to update an existing record. Returns number of rows updated.
     */
    private fun tryUpdate(entity: JdbcOutboxRecordEntity): Int =
        jdbcClient
            .sql(updateRecordQuery.trimIndent())
            .param(entity.status.name)
            .param(entity.recordKey)
            .param(entity.recordType)
            .param(entity.payload)
            .param(entity.context)
            .param(entity.partitionNo)
            .param(entity.createdAt)
            .param(entity.completedAt)
            .param(entity.failureCount)
            .param(entity.failureReason)
            .param(entity.nextRetryAt)
            .param(entity.handlerId)
            .param(entity.id)
            .update()

    /**
     * Inserts a new record into the database.
     */
    private fun insert(entity: JdbcOutboxRecordEntity) {
        jdbcClient
            .sql(insertRecordQuery.trimIndent())
            .param(entity.id)
            .param(entity.status.name)
            .param(entity.recordKey)
            .param(entity.recordType)
            .param(entity.payload)
            .param(entity.context)
            .param(entity.partitionNo)
            .param(entity.createdAt)
            .param(entity.completedAt)
            .param(entity.failureCount)
            .param(entity.failureReason)
            .param(entity.nextRetryAt)
            .param(entity.handlerId)
            .update()
    }

    /**
     * Finds all records with the specified status.
     */
    private fun findRecordsByStatus(status: OutboxRecordStatus): List<OutboxRecord<*>> =
        jdbcClient
            .sql(findByStatusQuery)
            .param(status.name)
            .query(JdbcOutboxRecordEntity::class.java)
            .list()
            .filterNotNull()
            .map { entityMapper.map(it) }

    /**
     * Builds the query for finding record keys with dynamic partition placeholders.
     */
    private fun buildRecordKeysQuery(
        partitions: Set<Int>,
        ignoreRecordKeysWithPreviousFailure: Boolean,
    ): String {
        val partitionPlaceholders = partitions.joinToString(",") { "?" }
        val template =
            if (ignoreRecordKeysWithPreviousFailure) {
                recordKeysQueryWithPreviousFailureFilterTemplate
            } else {
                recordKeysQueryWithoutPreviousFailureFilterTemplate
            }

        return template.format(partitionPlaceholders).trimIndent()
    }
}
