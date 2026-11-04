package io.namastack.outbox.query

/**
 * MariaDB statements: the portable statements with `LIMIT`. Separate from MySQL so that statements can
 * follow the MariaDB planner.
 *
 * @author Aleksander Zamojski
 * @since 1.12.0
 */
internal class MariaDbJdbcOutboxRecordQueries(
    tableName: String,
) : DefaultJdbcOutboxRecordQueries(tableName, "LIMIT :limit")
