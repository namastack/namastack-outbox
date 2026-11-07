package io.namastack.outbox.query

import io.namastack.outbox.OutboxRecordStatus
import io.namastack.outbox.toSingleLine

/**
 * PostgreSQL statements: `LIMIT`, the bulk record load as one ordered index probe per key, and processing
 * statements and counts that the partial indexes can serve.
 *
 * @author Aleksander Zamojski
 * @since 1.12.0
 */
internal class PostgresJdbcOutboxRecordQueries(
    tableName: String,
) : DefaultJdbcOutboxRecordQueries(tableName, "LIMIT :limit") {
    // `OFFSET 0` keeps the `LATERAL` subquery from being merged into a plain join, which under load gets a
    // slow plan reading all rows of the status, so it must not be removed.
    override val findIncompleteByKeysAndStatus: String =
        """
        SELECT o.*
        FROM (
          SELECT t.k
          FROM unnest(CAST(ARRAY[:recordKeys] AS varchar[])) AS t(k)
          ORDER BY t.k
        ) k
        CROSS JOIN LATERAL (
          SELECT r.*
          FROM $tableName r
          WHERE r.record_key = k.k
            AND r.status = :status
            AND r.completed_at IS NULL
          OFFSET 0
        ) o
        ORDER BY k.k, o.created_at, o.id
        """.toSingleLine()

    /**
     * Repeats `o.completed_at IS NULL`, because the partial indexes serve a statement only if it repeats their
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
     * Counts `NEW` and `FAILED` through the partial indexes, which serve a statement only if it repeats their
     * `completed_at IS NULL` condition. That condition would make `COMPLETED` count as zero, so a request that
     * includes `COMPLETED` uses the default statement, which reads the retained history.
     */
    override fun countByStatuses(statuses: Set<OutboxRecordStatus>): String =
        if (OutboxRecordStatus.COMPLETED !in statuses) {
            countIncompleteByStatuses
        } else {
            super.countByStatuses(statuses)
        }

    private val countIncompleteByPartitionsStatus: String =
        """
        SELECT COUNT(*)
        FROM $tableName
        WHERE partition_no IN (:partitions)
          AND status = :status
          AND completed_at IS NULL
        """.toSingleLine()

    /**
     * Counts `NEW` or `FAILED` through the partial indexes, and `COMPLETED` with the default statement, for the
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
     * an ordered scan of the partial index that stops at the first matching row.
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
