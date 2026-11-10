package io.namastack.outbox.query

/**
 * MySQL statements: the portable statements with `LIMIT`.
 *
 * @author Aleksander Zamojski
 * @since 1.12.0
 */
internal class MySqlJdbcOutboxRecordQueries(
    tableName: String,
) : DefaultJdbcOutboxRecordQueries(tableName, "LIMIT :limit")
