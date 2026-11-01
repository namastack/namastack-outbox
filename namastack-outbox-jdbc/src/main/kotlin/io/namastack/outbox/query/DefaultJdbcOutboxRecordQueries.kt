package io.namastack.outbox.query

import io.namastack.outbox.OutboxRecordStatus
import io.namastack.outbox.toSingleLine

/**
 * Shared SQL for one resolved outbox record table. Dialects override only their differing statements.
 *
 * Each supported dialect supplies its SQL row-limit syntax for bounded reads. A dialect may
 * override any statement, while common CRUD statements remain here. Every implementation must
 * preserve the ordering and retry-barrier contract of [JdbcOutboxRecordQueries].
 *
 * The optimized record-key statements rely on the invariant that a record is
 * `NEW` or `FAILED` exactly when its `completed_at` is `NULL`.
 *
 * Named parameters: `partitions`, `status`, `now`, `recordKeys`, `cutoff` and `limit`.
 *
 * @param tableName Resolved name of the record table
 * @param limitClause Builds the SQL row-limit clause, or an empty string for the JDBC-limited fallback
 *
 * @author Aleksander Zamojski
 * @since 1.12.0
 */
internal abstract class DefaultJdbcOutboxRecordQueries(
    protected val tableName: String,
    private val limitClause: (parameter: String) -> String,
) : JdbcOutboxRecordQueries {
    /** Updates all columns of an existing record. */
    override val updateRecord: String =
        """
        UPDATE $tableName
        SET status = :status, record_key = :recordKey, record_type = :recordType, payload = :payload, context = :context,
            partition_no = :partitionNo, created_at = :createdAt, completed_at = :completedAt, failure_count = :failureCount,
            failure_reason = :failureReason, next_retry_at = :nextRetryAt, handler_id = :handlerId
        WHERE id = :id
        """.toSingleLine()

    /** Updates the processing state of an existing record. */
    override val updateProcessingState: String =
        """
        UPDATE $tableName
        SET status = :status, completed_at = :completedAt, failure_count = :failureCount,
            failure_reason = :failureReason, next_retry_at = :nextRetryAt
        WHERE id = :id
        """.toSingleLine()

    /** Inserts a new record. */
    override val insertRecord: String =
        """
        INSERT INTO $tableName
        (id, status, record_key, record_type, payload, context, partition_no,
         created_at, completed_at, failure_count, failure_reason, next_retry_at, handler_id)
        VALUES (:id, :status, :recordKey, :recordType, :payload, :context, :partitionNo,
         :createdAt, :completedAt, :failureCount, :failureReason, :nextRetryAt, :handlerId)
        """.toSingleLine()

    /** Selects records by status ordered by creation time and id. */
    private val findByStatusQuery: String =
        """
        SELECT *
        FROM $tableName
        WHERE status = :status
        ORDER BY created_at, id
        """.toSingleLine()

    override fun findByStatus(status: OutboxRecordStatus): String = findByStatusQuery

    /** Selects incomplete records by record key and status ordered by creation time and id. */
    override val findByKeyAndStatus: String =
        """
        SELECT *
        FROM $tableName
        WHERE record_key = :recordKey
          AND status = :status
          AND completed_at IS NULL
        ORDER BY created_at, id
        """.toSingleLine()

    /**
     * Selects the incomplete records of several keys with the given status. Rows of one key are
     * returned in creation-time and id order; the order between keys is not significant.
     */
    override val findByKeysAndStatus: String =
        """
        SELECT *
        FROM $tableName
        WHERE record_key IN (:recordKeys)
          AND status = :status
          AND completed_at IS NULL
        ORDER BY record_key, completed_at, created_at, id
        """.toSingleLine()

    /** Selects at most `limit` records by status completed before a cutoff, oldest first. */
    override val findByStatusAndCompletedBefore: String =
        """
        SELECT *
        FROM $tableName
        WHERE status = :status
          AND completed_at < :cutoff
        ORDER BY completed_at
        ${limitClause("limit")}
        """.toSingleLine()

    /** Selects at most `limit` records by status with the next retry time before a cutoff, oldest first. */
    override val findByStatusAndNextRetryBefore: String =
        """
        SELECT *
        FROM $tableName
        WHERE status = :status
          AND next_retry_at < :cutoff
        ORDER BY next_retry_at
        ${limitClause("limit")}
        """.toSingleLine()

    /** Selects at most `limit` ids by status completed before a cutoff, oldest first. */
    override val findIdsByStatusAndCompletedBefore: String =
        """
        SELECT id
        FROM $tableName
        WHERE status = :status
          AND completed_at < :cutoff
        ORDER BY completed_at
        ${limitClause("limit")}
        """.toSingleLine()

    /** Selects at most `limit` ids by status with the next retry time before a cutoff, oldest first. */
    override val findIdsByStatusAndNextRetryBefore: String =
        """
        SELECT id
        FROM $tableName
        WHERE status = :status
          AND next_retry_at < :cutoff
        ORDER BY next_retry_at
        ${limitClause("limit")}
        """.toSingleLine()

    /**
     * Selects at most `limit` keys whose first incomplete record is due, ordered by that record's
     * creation time and id. A key has at most one such head record, so no grouping is needed.
     *
     * Any earlier incomplete record of the key (`NEW` or `FAILED`, due or not) blocks a record.
     * `o.completed_at IS NULL` lets the incomplete-record index serve the outer scan in
     * `(created_at, id)` order, so the row limit stops it early. `earlier.created_at <= o.created_at`
     * turns the predecessor check into an index range on the record-key index.
     */
    override val findStrictRecordKeys: String =
        """
        SELECT o.record_key
        FROM $tableName o
        WHERE o.partition_no IN (:partitions)
          AND o.status = :status
          AND o.next_retry_at <= :now
          AND o.completed_at IS NULL
          AND NOT EXISTS (
            SELECT 1
            FROM $tableName earlier
            WHERE earlier.record_key = o.record_key
              AND earlier.completed_at IS NULL
              AND earlier.created_at <= o.created_at
              AND (
                earlier.created_at < o.created_at
                OR (earlier.created_at = o.created_at AND earlier.id < o.id)
              )
          )
        ORDER BY o.created_at, o.id
        ${limitClause("limit")}
        """.toSingleLine()

    /**
     * Selects at most `limit` keys with a due record, ordered by the creation time and id of the
     * first due record of each key.
     *
     * Only an earlier due record with the same status blocks a record, so the predecessor check
     * repeats the outer status and due conditions; `FAILED` and not yet due records do not block.
     */
    override val findNonStrictRecordKeys: String =
        """
        SELECT o.record_key
        FROM $tableName o
        WHERE o.partition_no IN (:partitions)
          AND o.status = :status
          AND o.next_retry_at <= :now
          AND o.completed_at IS NULL
          AND NOT EXISTS (
            SELECT 1
            FROM $tableName earlier
            WHERE earlier.record_key = o.record_key
              AND earlier.completed_at IS NULL
              AND earlier.status = :status
              AND earlier.next_retry_at <= :now
              AND earlier.created_at <= o.created_at
              AND (
                earlier.created_at < o.created_at
                OR (earlier.created_at = o.created_at AND earlier.id < o.id)
              )
          )
        ORDER BY o.created_at, o.id
        ${limitClause("limit")}
        """.toSingleLine()

    /**
     * Preserves the original strict selection for a zero batch size. Grouping returns each key once
     * without requiring dialect-specific syntax for an unlimited result.
     */
    override val findStrictRecordKeysWithoutLimit: String =
        """
        SELECT o.record_key
        FROM $tableName o
        WHERE o.partition_no IN (:partitions)
          AND o.status = :status
          AND o.next_retry_at <= :now
          AND NOT EXISTS (
            SELECT 1
            FROM $tableName earlier
            WHERE earlier.record_key = o.record_key
              AND earlier.completed_at IS NULL
              AND (
                earlier.created_at < o.created_at
                OR (earlier.created_at = o.created_at AND earlier.id < o.id)
              )
          )
        GROUP BY o.record_key
        ORDER BY MIN(o.created_at) ASC, MIN(o.id) ASC
        """.toSingleLine()

    /** Preserves the original non-strict selection for a zero batch size. */
    override val findNonStrictRecordKeysWithoutLimit: String =
        """
        SELECT o.record_key
        FROM $tableName o
        WHERE o.partition_no IN (:partitions)
          AND o.status = :status
          AND o.next_retry_at <= :now
        GROUP BY o.record_key
        ORDER BY MIN(o.created_at) ASC
        """.toSingleLine()

    /** Counts records by status. */
    override val countByStatus: String =
        """
        SELECT COUNT(*)
        FROM $tableName
        WHERE status = :status
        """.toSingleLine()

    /** Counts records of one partition by status. */
    override val countByPartitionStatus: String =
        """
        SELECT COUNT(*)
        FROM $tableName
        WHERE partition_no = :partitionNo
          AND status = :status
        """.toSingleLine()

    /** Counts records across partitions by status. */
    override val countByPartitionsStatus: String =
        """
        SELECT COUNT(*)
        FROM $tableName
        WHERE partition_no IN (:partitions)
          AND status = :status
        """.toSingleLine()

    /**
     * Selects the earliest creation time of records with the given status. Returns at most
     * one row; no row maps to `null`.
     *
     * The ordered single-row read lets planners that do not turn `MIN` into an index read (MySQL,
     * MariaDB) stop at the first matching index entry.
     */
    private val oldestCreatedAtByPartitionsStatusQuery: String =
        """
        SELECT created_at
        FROM $tableName
        WHERE partition_no IN (:partitions)
          AND status = :status
        ORDER BY created_at
        ${limitClause("limit")}
        """.toSingleLine()

    override fun oldestCreatedAtByPartitionsStatus(status: OutboxRecordStatus): String =
        oldestCreatedAtByPartitionsStatusQuery

    /**
     * Selects the earliest next retry time of due records that are the first incomplete record of
     * their key. Returns at most one row; no row maps to `null`.
     *
     * Ordering by retry time allows an index on `(status, next_retry_at)` to stop at the first
     * eligible record. The chosen execution plan depends on the database and its statistics.
     */
    override val findOldestStrictDueRetry: String =
        """
        SELECT o.next_retry_at
        FROM $tableName o
        WHERE o.partition_no IN (:partitions)
          AND o.status = :status
          AND o.next_retry_at <= :now
          AND NOT EXISTS (
            SELECT 1
            FROM $tableName earlier
            WHERE earlier.record_key = o.record_key
              AND earlier.completed_at IS NULL
              AND earlier.created_at <= o.created_at
              AND (
                earlier.created_at < o.created_at
                OR (earlier.created_at = o.created_at AND earlier.id < o.id)
              )
          )
        ORDER BY o.next_retry_at
        ${limitClause("limit")}
        """.toSingleLine()

    /** Selects the earliest next retry time of due records. */
    private val findOldestDueRetryQuery: String =
        """
        SELECT MIN(o.next_retry_at)
        FROM $tableName o
        WHERE o.partition_no IN (:partitions)
          AND o.status = :status
          AND o.next_retry_at <= :now
        """.toSingleLine()

    override fun findOldestDueRetry(status: OutboxRecordStatus): String = findOldestDueRetryQuery

    /** Deletes records by status. */
    private val deleteByStatusQuery: String =
        """
        DELETE FROM $tableName
        WHERE status = :status
        """.toSingleLine()

    override fun deleteByStatus(status: OutboxRecordStatus): String = deleteByStatusQuery

    /** Deletes records by record key and status. */
    override val deleteByKeyAndStatus: String =
        """
        DELETE FROM $tableName
        WHERE record_key = :recordKey
          AND status = :status
        """.toSingleLine()

    /** Deletes one record by id. */
    override val deleteById: String =
        """
        DELETE FROM $tableName
        WHERE id = :id
        """.toSingleLine()

    /** Deletes records by ids. */
    override val deleteByIds: String =
        """
        DELETE FROM $tableName
        WHERE id IN (:ids)
        """.toSingleLine()
}
