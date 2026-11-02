package io.namastack.outbox.query

import io.namastack.outbox.toSingleLine

/**
 * H2 statements with bounded key reads and an array parameter for bulk record loading.
 *
 * The requested array becomes a small table of keys. Matching IDs are deduplicated before loading
 * payloads, preserving IN-list semantics when a custom collation considers requested keys equal.
 * Future retries remain in the returned pages for the worker to evaluate.
 *
 * @author Roland Beisel
 * @since 1.12.0
 */
internal class H2JdbcOutboxRecordQueries(
    tableName: String,
) : DefaultJdbcOutboxRecordQueries(tableName, { "LIMIT :$it" }) {
    override fun recordKeysParameter(recordKeys: Set<String>): Any = recordKeys.toTypedArray()

    // Keep eligibility in the ID lookup. Repeating it on the payload join makes H2 prefer a
    // status-driven scan instead of looking up the already selected IDs by primary key.
    override val findByKeysAndStatus: String =
        """
        SELECT record.*
        FROM (
            SELECT DISTINCT pending.id
            FROM TABLE(record_key VARCHAR = :recordKeys) requested
            JOIN $tableName pending ON pending.record_key = requested.record_key
            WHERE pending.completed_at IS NULL
              AND pending.status = :status
        ) matching
        JOIN $tableName record ON record.id = matching.id
        ORDER BY record.record_key, record.created_at, record.id
    """.toSingleLine()
}
