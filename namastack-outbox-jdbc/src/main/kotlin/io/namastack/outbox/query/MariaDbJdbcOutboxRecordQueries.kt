package io.namastack.outbox.query

/**
 * MariaDB statements, sharing the default SQL and adding LIMIT to bounded reads.
 *
 * Kept separate from MySQL so MariaDB-specific improvements can be added independently.
 *
 * @author Roland Beisel
 * @since 1.12.0
 */
internal class MariaDbJdbcOutboxRecordQueries(
    tableName: String,
) : DefaultJdbcOutboxRecordQueries(tableName, { "LIMIT :$it" })
