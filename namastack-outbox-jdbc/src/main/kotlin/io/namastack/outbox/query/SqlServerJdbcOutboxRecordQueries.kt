package io.namastack.outbox.query

/**
 * SQL Server statements: the portable statements with `OFFSET 0 ROWS FETCH NEXT ... ROWS ONLY`.
 *
 * @author Aleksander Zamojski
 * @since 1.12.0
 */
internal class SqlServerJdbcOutboxRecordQueries(
    tableName: String,
) : DefaultJdbcOutboxRecordQueries(tableName, "OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY")
