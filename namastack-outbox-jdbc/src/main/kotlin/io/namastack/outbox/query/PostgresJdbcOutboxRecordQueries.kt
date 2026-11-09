package io.namastack.outbox.query

import io.namastack.outbox.toSingleLine

/**
 * PostgreSQL statements: a `LIMIT` row-limit clause and the bulk record load as one ordered index probe per
 * key. All other statements are those of [DefaultJdbcOutboxRecordQueries], which the composite indexes of the
 * PostgreSQL schema script serve.
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
}
