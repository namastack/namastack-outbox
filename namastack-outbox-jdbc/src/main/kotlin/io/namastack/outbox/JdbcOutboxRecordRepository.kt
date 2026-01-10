package io.namastack.outbox

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForList
import org.springframework.jdbc.core.queryForObject
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime

/**
 * JDBC implementation of the OutboxRecordRepository and OutboxRecordStatusRepository interfaces.
 *
 * This implementation uses Spring's JdbcTemplate to persist and query outbox records
 * from a relational database.
 *
 * @param jdbcTemplate JDBC template for database operations
 * @param transactionTemplate Transaction template for programmatic transaction management
 * @param recordSerializer Serializer for payload and context
 * @param clock Clock for time-based operations
 *
 * @author Roland Beisel
 * @since 1.1.0
 */
internal open class JdbcOutboxRecordRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val recordSerializer: OutboxPayloadSerializer,
    private val clock: java.time.Clock,
) : OutboxRecordRepository,
    OutboxRecordStatusRepository {
    private val rowMapper = OutboxRecordRowMapper(recordSerializer)

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
            val payload = record.payload ?: throw IllegalArgumentException("record payload cannot be null")
            val serializedPayload = recordSerializer.serialize(payload)
            val recordType = payload.javaClass.name
            val serializedContext = serializeContext(record.context)

            val updated = tryUpdate(record, recordType, serializedPayload, serializedContext)

            if (updated == 0) {
                insert(record, recordType, serializedPayload, serializedContext)
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
        jdbcTemplate.query(
            findByKeyAndStatusQuery,
            rowMapper,
            recordKey,
            OutboxRecordStatus.NEW.name,
        )

    /**
     * Counts outbox records by status.
     */
    override fun countByStatus(status: OutboxRecordStatus): Long =
        jdbcTemplate.queryForObject(countByStatusQuery, Long::class.java, status.name) ?: 0L

    /**
     * Counts outbox records by partition and status.
     */
    override fun countRecordsByPartition(
        partition: Int,
        status: OutboxRecordStatus,
    ): Long =
        jdbcTemplate.queryForObject<Long>(
            countByPartitionStatusQuery,
            partition,
            status.name,
        ) ?: 0L

    /**
     * Deletes all outbox records with the specified status.
     */
    override fun deleteByStatus(status: OutboxRecordStatus) {
        transactionTemplate.execute {
            jdbcTemplate.update(deleteByStatusQuery.trimIndent(), status.name)
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
            jdbcTemplate.update(deleteByKeyAndStatusQuery.trimIndent(), recordKey, status.name)
        }
    }

    /**
     * Deletes a single outbox record by ID.
     */
    override fun deleteById(id: String) {
        transactionTemplate.executeWithoutResult {
            jdbcTemplate.update(deleteByIdQuery.trimIndent(), id)
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
        val args = buildQueryArgs(partitions, status, now, batchSize)

        return jdbcTemplate
            .queryForList<String>(query, *args.toTypedArray())
            .filterNotNull()
    }

    /**
     * Attempts to update an existing record. Returns number of rows updated.
     */
    private fun <T> tryUpdate(
        record: OutboxRecord<T>,
        recordType: String,
        serializedPayload: String,
        serializedContext: String?,
    ): Int =
        jdbcTemplate.update(
            updateRecordQuery.trimIndent(),
            record.status.name,
            record.key,
            recordType,
            serializedPayload,
            serializedContext,
            record.partition,
            record.createdAt,
            record.completedAt,
            record.failureCount,
            record.failureReason,
            record.nextRetryAt,
            record.handlerId,
            record.id,
        )

    /**
     * Inserts a new record into the database.
     */
    private fun <T> insert(
        record: OutboxRecord<T>,
        recordType: String,
        serializedPayload: String,
        serializedContext: String?,
    ) {
        jdbcTemplate.update(
            insertRecordQuery.trimIndent(),
            record.id,
            record.status.name,
            record.key,
            recordType,
            serializedPayload,
            serializedContext,
            record.partition,
            record.createdAt,
            record.completedAt,
            record.failureCount,
            record.failureReason,
            record.nextRetryAt,
            record.handlerId,
        )
    }

    /**
     * Serializes context map to JSON string, returns null if empty.
     */
    private fun serializeContext(context: Map<String, String>): String? =
        context
            .takeIf { it.isNotEmpty() }
            ?.let { recordSerializer.serialize(it) }

    /**
     * Finds all records with the specified status.
     */
    private fun findRecordsByStatus(status: OutboxRecordStatus): List<OutboxRecord<*>> =
        jdbcTemplate.query(findByStatusQuery, rowMapper, status.name)

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

    /**
     * Builds the query arguments list for record keys query.
     */
    private fun buildQueryArgs(
        partitions: Set<Int>,
        status: OutboxRecordStatus,
        now: OffsetDateTime,
        batchSize: Int,
    ): List<Any> =
        mutableListOf<Any>().apply {
            addAll(partitions)
            add(status.name)
            add(now)
            add(batchSize)
        }
}
