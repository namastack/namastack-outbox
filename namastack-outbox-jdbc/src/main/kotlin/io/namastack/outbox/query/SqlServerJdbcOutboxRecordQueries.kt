package io.namastack.outbox.query

import io.namastack.outbox.OutboxRecordStatus
import io.namastack.outbox.toSingleLine

/**
 * SQL Server statements: `OFFSET 0 ROWS FETCH NEXT ... ROWS ONLY`, and processing statements and counts that
 * the filtered indexes can serve.
 *
 * @author Aleksander Zamojski
 * @since 1.12.0
 */
internal class SqlServerJdbcOutboxRecordQueries(
    tableName: String,
) : DefaultJdbcOutboxRecordQueries(tableName, "OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY") {
    /**
     * Repeats `o.completed_at IS NULL`, because the filtered indexes serve a statement only if it repeats their
     * condition. The index then serves the outer scan in `(created_at, id)` order, so the row limit stops it
     * early.
     */
    override val findStrictRecordKeys: String =
        """
        SELECT o.record_key
        FROM $tableName o
        WHERE o.partition_no IN (:partitions)
          AND o.status = :status
          AND o.next_retry_at <= :now
          AND o.completed_at IS NULL
          AND $noEarlierIncompleteRecord
        ORDER BY o.created_at, o.id
        $rowLimitClause
        """.toSingleLine()

    /** Repeats `o.completed_at IS NULL` for the reason given on [findStrictRecordKeys]. */
    override val findNonStrictRecordKeys: String =
        """
        SELECT o.record_key
        FROM $tableName o
        WHERE o.partition_no IN (:partitions)
          AND o.status = :status
          AND o.next_retry_at <= :now
          AND o.completed_at IS NULL
          AND $noEarlierDueRecord
        ORDER BY o.created_at, o.id
        $rowLimitClause
        """.toSingleLine()

    private val countIncompleteByStatuses: String =
        """
        SELECT status, COUNT(*)
        FROM $tableName
        WHERE status IN (:statuses)
          AND completed_at IS NULL
        GROUP BY status
        """.toSingleLine()

    /**
     * Counts `NEW` and `FAILED` through the filtered indexes, which serve a statement only if it repeats their
     * `completed_at IS NULL` condition. That condition would make `COMPLETED` count as zero, so a request that
     * includes `COMPLETED` uses the default statement, which reads the retained history.
     */
    override fun countByStatuses(statuses: Set<OutboxRecordStatus>): String =
        if (OutboxRecordStatus.COMPLETED !in statuses) countIncompleteByStatuses else super.countByStatuses(statuses)

    private val countIncompleteByPartitionsStatus: String =
        """
        SELECT COUNT(*)
        FROM $tableName
        WHERE partition_no IN (:partitions)
          AND status = :status
          AND completed_at IS NULL
        """.toSingleLine()

    /**
     * Counts `NEW` or `FAILED` through the filtered indexes, and `COMPLETED` with the default statement, for the
     * reason given on [countByStatuses].
     */
    override fun countByPartitionsStatus(status: OutboxRecordStatus): String =
        if (status != OutboxRecordStatus.COMPLETED) {
            countIncompleteByPartitionsStatus
        } else {
            super.countByPartitionsStatus(status)
        }

    /**
     * Repeats `completed_at IS NULL` for the reason given on [findStrictRecordKeys]. The planner reads `MIN` as
     * an ordered scan of the filtered index that stops at the first matching row.
     */
    override val oldestCreatedAtByPartitionsStatus: String =
        """
        SELECT MIN(created_at)
        FROM $tableName
        WHERE partition_no IN (:partitions)
          AND status = :status
          AND completed_at IS NULL
        """.toSingleLine()

    /** Repeats `o.completed_at IS NULL` for the reason given on [findStrictRecordKeys]. */
    override val oldestDueStrict: String =
        """
        SELECT o.next_retry_at
        FROM $tableName o
        WHERE o.partition_no IN (:partitions)
          AND o.status = :status
          AND o.next_retry_at <= :now
          AND o.completed_at IS NULL
          AND $noEarlierIncompleteRecord
        ORDER BY o.next_retry_at
        $rowLimitClause
        """.toSingleLine()

    /** Repeats `completed_at IS NULL` and uses `MIN` for the reasons given on [oldestCreatedAtByPartitionsStatus]. */
    override val oldestDueNonStrict: String =
        """
        SELECT MIN(next_retry_at)
        FROM $tableName
        WHERE partition_no IN (:partitions)
          AND status = :status
          AND next_retry_at <= :now
          AND completed_at IS NULL
        """.toSingleLine()
}
