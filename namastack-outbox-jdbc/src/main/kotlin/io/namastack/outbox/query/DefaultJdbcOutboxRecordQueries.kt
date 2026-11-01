package io.namastack.outbox.query

import io.namastack.outbox.toSingleLine

/**
 * Shared SQL for one resolved outbox record table. Dialects override only their differing statements.
 *
 * Defaults use portable SQL without a row-limit clause. The repository bounds results through JDBC
 * as well. A dialect supplies its limit syntax and may override any statement, while common CRUD
 * statements remain here. Every implementation must preserve the ordering and retry-barrier
 * contract of [JdbcOutboxRecordQueries].
 *
 * The record-key, oldest-due and oldest-created statements rely on the invariant that a record is
 * `NEW` or `FAILED` exactly when its `completed_at` is `NULL`.
 *
 * Named parameters: `partitions`, `status`, `now`, `recordKeys`, `cutoff` and `limit`.
 * Statements that take `limit` may ignore it (portable variant); binding it is always safe.
 *
 * @param tableName Resolved name of the record table
 * @param limitClause Builds the trailing row-limit clause for a named parameter, or an empty string
 * @param predecessorSource Source exposing predecessor columns, correlated to the outer alias `o`
 *
 * @author Aleksander Zamojski
 * @since 1.12.0
 */
internal open class DefaultJdbcOutboxRecordQueries(
    protected val tableName: String,
    private val limitClause: (parameter: String) -> String = { "" },
    private val predecessorSource: String = "$tableName earlier",
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
    override val findByStatus: String =
        """
        SELECT *
        FROM $tableName
        WHERE status = :status
        ORDER BY created_at, id
        """.toSingleLine()

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
            FROM $predecessorSource
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
            FROM $predecessorSource
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
     * Selects the earliest creation time of incomplete records with the given status. Returns at most
     * one row; no row maps to `null`.
     *
     * The ordered single-row read lets planners that do not turn `MIN` into an index read (MySQL,
     * MariaDB) stop at the first matching index entry.
     */
    override val oldestCreatedAtByPartitionsStatus: String =
        """
        SELECT created_at
        FROM $tableName
        WHERE partition_no IN (:partitions)
          AND status = :status
          AND completed_at IS NULL
        ORDER BY created_at
        ${limitClause("limit")}
        """.toSingleLine()

    /**
     * Selects the earliest next retry time of due records that are the first incomplete record of
     * their key. Returns at most one row; no row maps to `null`.
     *
     * The outer scan reads `(status, next_retry_at)` in retry order and stops at the first record that
     * passes the predecessor check, so it has no `o.completed_at IS NULL` condition.
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
    override val findOldestDueRetry: String =
        """
        SELECT MIN(o.next_retry_at)
        FROM $tableName o
        WHERE o.partition_no IN (:partitions)
          AND o.status = :status
          AND o.next_retry_at <= :now
        """.toSingleLine()

    /** Deletes records by status. */
    override val deleteByStatus: String =
        """
        DELETE FROM $tableName
        WHERE status = :status
        """.toSingleLine()

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
