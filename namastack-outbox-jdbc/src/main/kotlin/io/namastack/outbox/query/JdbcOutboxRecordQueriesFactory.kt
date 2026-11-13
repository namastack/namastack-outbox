package io.namastack.outbox.query

import io.namastack.outbox.config.JdbcDatabaseType

/**
 * Selects the record statements for a database type. Opens no database connection; the caller decides
 * when the database type is resolved.
 *
 * @author Aleksander Zamojski
 * @since 2.0.0
 */
internal object JdbcOutboxRecordQueriesFactory {
    /**
     * Returns the statements for the given database type, or the portable statements when it is unknown.
     *
     * @param databaseType Resolved database type, or `null` if the product is not supported
     * @param tableName Resolved name of the record table
     * @return Statements for the database type
     */
    fun create(
        databaseType: JdbcDatabaseType?,
        tableName: String,
    ): JdbcOutboxRecordQueries =
        when (databaseType) {
            JdbcDatabaseType.PostgreSQL -> PostgresJdbcOutboxRecordQueries(tableName)
            JdbcDatabaseType.MySQL -> MySqlJdbcOutboxRecordQueries(tableName)
            JdbcDatabaseType.MariaDB -> MariaDbJdbcOutboxRecordQueries(tableName)
            JdbcDatabaseType.SQLServer -> SqlServerJdbcOutboxRecordQueries(tableName)
            JdbcDatabaseType.Oracle -> OracleJdbcOutboxRecordQueries(tableName)
            JdbcDatabaseType.H2 -> H2JdbcOutboxRecordQueries(tableName)
            null -> DefaultJdbcOutboxRecordQueries(tableName)
        }
}
