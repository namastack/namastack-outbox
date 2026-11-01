package io.namastack.outbox.query

/**
 * SQL Server statements: the standard statements with `OFFSET 0 ROWS FETCH NEXT ... ROWS ONLY`.
 *
 * @author Aleksander Zamojski
 * @since 1.12.0
 */
internal class SqlServerJdbcOutboxRecordQueries(
    tableName: String,
) : DefaultJdbcOutboxRecordQueries(tableName, { "OFFSET 0 ROWS FETCH NEXT :$it ROWS ONLY" })
