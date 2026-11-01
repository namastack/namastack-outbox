package io.namastack.outbox.query

/**
 * MariaDB statements: the standard statements with `LIMIT`. Separate from MySQL so that MariaDB
 * statements can follow its own planner.
 *
 * @author Aleksander Zamojski
 * @since 1.12.0
 */
internal class MariaDbJdbcOutboxRecordQueries(
    tableName: String,
) : DefaultJdbcOutboxRecordQueries(tableName, { "LIMIT :$it" })
