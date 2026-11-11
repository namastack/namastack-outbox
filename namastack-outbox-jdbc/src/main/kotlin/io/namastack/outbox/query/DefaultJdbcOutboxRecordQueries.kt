package io.namastack.outbox.query

import io.namastack.outbox.toSingleLine

/**
 * Portable SQL statements of [JdbcOutboxRecordQueries] for one resolved record table.
 *
 * Used directly for databases without a dialect class. It then renders no row-limit clause, and the driver
 * applies the row limit through `withMaxRows`. Dialect classes supply their row-limit clause and override
 * single statements to adapt them to their planner and indexes.
 *
 * The statements that read `NEW` records by status select incomplete records through the status alone,
 * because `completed_at` is `NULL` exactly for `NEW` and `FAILED` records. This lets status-leading composite
 * indexes without `completed_at` serve them. The predecessor checks keep `completed_at IS NULL`, because an
 * earlier `FAILED` record also blocks its key.
 *
 * @param tableName Resolved name of the record table
 * @param rowLimitClause Clause appended after `ORDER BY` to cap the result at the `:limit` parameter, or an
 * empty string for none
 *
 * @author Aleksander Zamojski
 * @since 1.12.0
 */
internal open class DefaultJdbcOutboxRecordQueries(
    protected val tableName: String,
    protected val rowLimitClause: String = "",
) : JdbcOutboxRecordQueries {
    /**
     * Rejects a record `o` if its key has an earlier incomplete record, due or not, with any status.
     *
     * `earlier.created_at <= o.created_at` turns the predecessor check into an index range on the
     * record-key index. It is implied by the condition that follows it.
     */
    protected val noEarlierIncompleteRecord: String =
        """
        NOT EXISTS (
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
        """.toSingleLine()

    /**
     * Rejects a record `o` if its key has an earlier due record with the same status, which leaves the
     * first due record of each key.
     */
    protected val noEarlierDueRecord: String =
        """
        NOT EXISTS (
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
        """.toSingleLine()

    override val updateProcessingState: String =
        """
        UPDATE $tableName
        SET status = :status,
            completed_at = :completedAt,
            failure_count = :failureCount,
            failure_reason = :failureReason,
            next_retry_at = :nextRetryAt
        WHERE id = :id
        """.toSingleLine()

    override val insertRecord: String =
        """
        INSERT INTO $tableName
        (
          id, status, record_key, record_type, payload, context, partition_no,
          created_at, completed_at, failure_count, failure_reason, next_retry_at, handler_id
        )
        VALUES (
          :id, :status, :recordKey, :recordType, :payload, :context, :partitionNo,
          :createdAt, :completedAt, :failureCount, :failureReason, :nextRetryAt, :handlerId
        )
        """.toSingleLine()

    override val findByStatus: String =
        """
        SELECT *
        FROM $tableName
        WHERE status = :status
        ORDER BY created_at, id
        """.toSingleLine()

    override val findIncompleteByKeyAndStatus: String =
        """
        SELECT *
        FROM $tableName
        WHERE record_key = :recordKey
          AND status = :status
          AND completed_at IS NULL
        ORDER BY created_at, id
        """.toSingleLine()

    override val findIncompleteByKeysAndStatus: String =
        """
        SELECT *
        FROM $tableName
        WHERE record_key IN (:recordKeys)
          AND status = :status
          AND completed_at IS NULL
        ORDER BY record_key, created_at, id
        """.toSingleLine()

    override val findByStatusAndCompletedBefore: String =
        """
        SELECT *
        FROM $tableName
        WHERE status = :status
          AND completed_at < :cutoff
        ORDER BY completed_at
        $rowLimitClause
        """.toSingleLine()

    override val findByStatusAndNextRetryBefore: String =
        """
        SELECT *
        FROM $tableName
        WHERE status = :status
          AND next_retry_at < :cutoff
        ORDER BY next_retry_at
        $rowLimitClause
        """.toSingleLine()

    override val findIdsByStatusAndCompletedBefore: String =
        """
        SELECT id
        FROM $tableName
        WHERE status = :status
          AND completed_at < :cutoff
        ORDER BY completed_at
        $rowLimitClause
        """.toSingleLine()

    override val findIdsByStatusAndNextRetryBefore: String =
        """
        SELECT id
        FROM $tableName
        WHERE status = :status
          AND next_retry_at < :cutoff
        ORDER BY next_retry_at
        $rowLimitClause
        """.toSingleLine()

    /**
     * A key has at most one record without an earlier incomplete record, so no grouping is needed. The
     * status index serves the outer scan in `(created_at, id)` order, so the row limit stops it early.
     */
    override val findStrictRecordKeys: String =
        """
        SELECT o.record_key
        FROM $tableName o
        WHERE o.partition_no IN (:partitions)
          AND o.status = :status
          AND o.next_retry_at <= :now
          AND $noEarlierIncompleteRecord
        ORDER BY o.created_at, o.id
        $rowLimitClause
        """.toSingleLine()

    override val findNonStrictRecordKeys: String =
        """
        SELECT o.record_key
        FROM $tableName o
        WHERE o.partition_no IN (:partitions)
          AND o.status = :status
          AND o.next_retry_at <= :now
          AND $noEarlierDueRecord
        ORDER BY o.created_at, o.id
        $rowLimitClause
        """.toSingleLine()

    override val countByStatus: String =
        """
        SELECT COUNT(*)
        FROM $tableName
        WHERE status = :status
        """.toSingleLine()

    override val countByStatuses: String =
        """
        SELECT status, COUNT(*)
        FROM $tableName
        WHERE status IN (:statuses)
        GROUP BY status
        """.toSingleLine()

    override val countByPartitionStatus: String =
        """
        SELECT COUNT(*)
        FROM $tableName
        WHERE partition_no = :partitionNo
          AND status = :status
        """.toSingleLine()

    override val countByPartitionsStatus: String =
        """
        SELECT COUNT(*)
        FROM $tableName
        WHERE partition_no IN (:partitions)
          AND status = :status
        """.toSingleLine()

    override val oldestCreatedAtByPartitionsStatus: String =
        """
        SELECT created_at
        FROM $tableName
        WHERE partition_no IN (:partitions)
          AND status = :status
        ORDER BY created_at
        $rowLimitClause
        """.toSingleLine()

    override val oldestDueStrict: String =
        """
        SELECT o.next_retry_at
        FROM $tableName o
        WHERE o.partition_no IN (:partitions)
          AND o.status = :status
          AND o.next_retry_at <= :now
          AND $noEarlierIncompleteRecord
        ORDER BY o.next_retry_at
        $rowLimitClause
        """.toSingleLine()

    override val oldestDueNonStrict: String =
        """
        SELECT next_retry_at
        FROM $tableName
        WHERE partition_no IN (:partitions)
          AND status = :status
          AND next_retry_at <= :now
        ORDER BY next_retry_at
        $rowLimitClause
        """.toSingleLine()

    override val deleteByStatus: String =
        """
        DELETE FROM $tableName
        WHERE status = :status
        """.toSingleLine()

    override val deleteByKeyAndStatus: String =
        """
        DELETE FROM $tableName
        WHERE record_key = :recordKey
          AND status = :status
        """.toSingleLine()

    override val deleteById: String =
        """
        DELETE FROM $tableName
        WHERE id = :id
        """.toSingleLine()

    override val deleteByIds: String =
        """
        DELETE FROM $tableName
        WHERE id IN (:ids)
        """.toSingleLine()
}
