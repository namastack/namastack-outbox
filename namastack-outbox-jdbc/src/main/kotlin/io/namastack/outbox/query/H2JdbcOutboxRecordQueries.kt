package io.namastack.outbox.query

/**
 * H2 statements: the portable statements with `FETCH FIRST ... ROWS ONLY`.
 *
 * @author Aleksander Zamojski
 * @since 2.0.0
 */
internal class H2JdbcOutboxRecordQueries(
    tableName: String,
) : DefaultJdbcOutboxRecordQueries(tableName, "FETCH FIRST :limit ROWS ONLY")
