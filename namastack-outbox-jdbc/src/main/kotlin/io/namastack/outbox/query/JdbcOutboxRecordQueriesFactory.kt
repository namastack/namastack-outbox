package io.namastack.outbox.query

import io.namastack.outbox.config.JdbcDatabaseType

/**
 * Selects the record SQL dialect at the JDBC wiring boundary.
 *
 * A new database implementation needs one query class and one entry here. Repositories and
 * processing code continue using the same query contract. Unsupported products retain portable SQL.
 * This factory never opens a database connection; callers choose when to resolve metadata.
 *
 * @author Roland Beisel
 * @since 1.12.0
 */
internal object JdbcOutboxRecordQueriesFactory {
    /**
     * Creates immutable statements for one resolved table namespace.
     *
     * @param databaseType Supported database type, or null for portable JDBC statements
     * @param tableName Fully resolved, trusted table identifier supplied by the table name resolver
     * @return Statements implementing the same record-selection and ordering contract
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
