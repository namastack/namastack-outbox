package io.namastack.outbox.query

import io.namastack.outbox.JdbcTableNameResolver
import io.namastack.outbox.config.JdbcDatabaseType

/**
 * Selects the record SQL dialect at the JDBC wiring boundary.
 *
 * A new database implementation needs one query class and one entry here. Repositories and
 * processing code continue using the same query contract. Unrecognized products use the portable fallback.
 * The table name is captured once from the resolver during construction. This factory never opens
 * a database connection; callers choose when to resolve metadata.
 *
 * @param tableNameResolver Resolver providing the fixed physical record-table namespace
 *
 * @author Roland Beisel
 * @since 1.12.0
 */
internal class JdbcOutboxRecordQueriesFactory(
    tableNameResolver: JdbcTableNameResolver,
) {
    private val tableName = tableNameResolver.outboxRecord

    /**
     * Creates immutable statements for one resolved table namespace.
     *
     * @param databaseType Detected database type, or `null` for portable SQL
     * @return Statements implementing the same record-selection and ordering contract
     */
    fun create(databaseType: JdbcDatabaseType?): JdbcOutboxRecordQueries =
        when (databaseType) {
            JdbcDatabaseType.PostgreSQL -> PostgresJdbcOutboxRecordQueries(tableName)
            JdbcDatabaseType.MySQL -> MySqlJdbcOutboxRecordQueries(tableName)
            JdbcDatabaseType.MariaDB -> MariaDbJdbcOutboxRecordQueries(tableName)
            JdbcDatabaseType.SQLServer -> SqlServerJdbcOutboxRecordQueries(tableName)
            JdbcDatabaseType.Oracle -> OracleJdbcOutboxRecordQueries(tableName)
            JdbcDatabaseType.H2 -> H2JdbcOutboxRecordQueries(tableName)
            null -> GenericJdbcOutboxRecordQueries(tableName)
        }
}
