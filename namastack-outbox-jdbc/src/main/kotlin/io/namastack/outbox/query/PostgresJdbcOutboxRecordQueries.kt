package io.namastack.outbox.query

import io.namastack.outbox.toSingleLine

/**
 * PostgreSQL statements: `LIMIT`, and the bulk record load as one ordered index probe per key.
 *
 * @author Aleksander Zamojski
 * @since 1.12.0
 */
internal class PostgresJdbcOutboxRecordQueries(
    tableName: String,
) : DefaultJdbcOutboxRecordQueries(tableName, { "LIMIT :$it" }) {
    override val findByKeysAndStatus: String =
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
          ORDER BY r.completed_at, r.created_at, r.id
        ) o
        """.toSingleLine()
}
