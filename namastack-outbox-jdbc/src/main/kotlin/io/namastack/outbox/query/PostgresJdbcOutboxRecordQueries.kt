package io.namastack.outbox.query

import io.namastack.outbox.OutboxRecordStatus
import io.namastack.outbox.toSingleLine

/**
 * PostgreSQL statements: `LIMIT`, the bulk record load as one ordered index probe per key, and counts
 * that the partial indexes can serve.
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
}
