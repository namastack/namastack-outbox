package io.namastack.outbox.query

import io.namastack.outbox.toSingleLine

/**
 * PostgreSQL statements: a `LIMIT` row-limit clause, the bulk record load as one ordered index probe per key,
 * and the oldest-record statements that select one `MIN` aggregate row. All other statements are those of
 * [DefaultJdbcOutboxRecordQueries], which the composite indexes of the PostgreSQL schema script serve.
 *
 * @author Aleksander Zamojski
 * @since 2.0.0
 */
internal class PostgresJdbcOutboxRecordQueries(
    tableName: String,
) : DefaultJdbcOutboxRecordQueries(tableName, "LIMIT :limit") {
    // `OFFSET 0` preserves the per-key subquery and keeps the outer status filter from being pushed in.
    // With stale statistics, an inner status filter can select a global status scan for every key.
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
            AND r.completed_at IS NULL
          OFFSET 0
        ) o
        WHERE o.status = :status
        ORDER BY k.k, o.created_at, o.id
        """.toSingleLine()

    /** Selects `MIN`, which the planner reads as an ordered index scan that stops at the first matching row. */
    override val oldestCreatedAtByPartitionsStatus: String =
        """
        SELECT MIN(created_at)
        FROM $tableName
        WHERE partition_no IN (:partitions)
          AND status = :status
        """.toSingleLine()

    /** Selects `MIN` for the reason given on [oldestCreatedAtByPartitionsStatus]. */
    override val oldestDueNonStrict: String =
        """
        SELECT MIN(next_retry_at)
        FROM $tableName
        WHERE partition_no IN (:partitions)
          AND status = :status
          AND next_retry_at <= :now
        """.toSingleLine()
}
