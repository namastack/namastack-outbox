package io.namastack.outbox.query

/**
 * H2 statements: the standard statements with `LIMIT`.
 *
 * @author Aleksander Zamojski
 * @since 1.12.0
 */
internal class H2JdbcOutboxRecordQueries(
    tableName: String,
) : DefaultJdbcOutboxRecordQueries(tableName, { "LIMIT :$it" })
