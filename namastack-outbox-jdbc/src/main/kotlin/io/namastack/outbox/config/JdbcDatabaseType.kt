package io.namastack.outbox.config

import org.springframework.jdbc.support.JdbcUtils
import java.sql.DatabaseMetaData
import javax.sql.DataSource

/**
 * Sealed class representing supported database types.
 *
 * Each database type specifies the location of its SQL schema initialization script.
 * This enables database-agnostic schema creation with database-specific SQL variants.
 *
 * Supported Databases:
 * - **PostgreSQL**: Advanced relational database with strong ACID guarantees
 * - **MySQL**: Popular open-source relational database
 * - **H2**: In-memory/file-based database for testing and development
 * - **MariaDB**: MySQL-compatible open-source database
 * - **SQL Server**: Microsoft's enterprise relational database
 *
 * @param schemaLocation Classpath location of the database-specific schema script
 *
 * @author Roland Beisel, Khalid Alharisi
 * @since 1.0.0
 */
sealed class JdbcDatabaseType(
    val schemaLocation: String,
    val statementSeparator: String = ";",
) {
    /**
     * PostgreSQL database type.
     *
     * Uses advanced PostgreSQL features like JSON operators, intervals, and
     * CTE (Common Table Expressions) for optimal partition management queries.
     */
    data object PostgreSQL : JdbcDatabaseType(
        schemaLocation = "classpath:schema/postgres/outbox-tables.sql",
    )

    /**
     * MySQL database type.
     *
     * Supports MySQL 8.0+ with InnoDB storage engine for transactional support
     * and row-level locking required for optimistic locking (version fields).
     */
    data object MySQL : JdbcDatabaseType(
        schemaLocation = "classpath:schema/mysql/outbox-tables.sql",
    )

    /**
     * H2 in-memory/file-based database type.
     *
     * Suitable for testing, development, and small deployments.
     * Provides quick feedback loop for integration tests.
     */
    data object H2 : JdbcDatabaseType(
        schemaLocation = "classpath:schema/h2/outbox-tables.sql",
    )

    /**
     * MariaDB database type.
     *
     * MySQL-compatible open-source database with improved performance
     * and additional features like JSON functions and window functions.
     */
    data object MariaDB : JdbcDatabaseType(
        schemaLocation = "classpath:schema/mariadb/outbox-tables.sql",
    )

    /**
     * SQL Server database type.
     *
     * Microsoft's enterprise relational database with T-SQL support.
     * SQL Server 2016+ is recommended for compatibility.
     */
    data object SQLServer : JdbcDatabaseType(
        schemaLocation = "classpath:schema/sqlserver/outbox-tables.sql",
    )

    /**
     * Oracle database type.
     *
     * Oracle Database is a high-performance, converged relational database designed to handle
     * complex enterprise workloads.
     *
     * The minimum supported Oracle Database version is 18c. Older versions may work, but they are not tested.
     */
    data object Oracle : JdbcDatabaseType(
        schemaLocation = "classpath:schema/oracle/outbox-tables.sql",
        statementSeparator = "/",
    )

    companion object {
        /**
         * Resolves a database type from its name.
         *
         * Performs case-insensitive matching against the database product names
         * reported by the JDBC driver's DatabaseMetaData.getDatabaseProductName().
         *
         * @param databaseName The database name/product name from JDBC
         * @return Corresponding JdbcDatabaseType
         * @throws IllegalArgumentException if database type is not supported
         */
        fun from(databaseName: String): JdbcDatabaseType =
            requireNotNull(resolve(databaseName)) { "Unsupported database type: $databaseName" }

        /**
         * Resolves the database type of a datasource from `DatabaseMetaData.getDatabaseProductName()`.
         *
         * Returns `null` for an unrecognized product so record queries can use portable SQL.
         * Missing product names and metadata-access failures are reported instead of being hidden.
         *
         * @param dataSource Datasource to inspect
         * @return Corresponding database type, or `null` for the generic query fallback
         * @throws IllegalStateException if the metadata contains no database product name
         * @throws org.springframework.jdbc.support.MetaDataAccessException if the metadata cannot be read
         * @since 1.12.0
         */
        internal fun detect(dataSource: DataSource): JdbcDatabaseType? {
            val productName =
                JdbcUtils.extractDatabaseMetaData(dataSource, DatabaseMetaData::getDatabaseProductName)
            val databaseName =
                checkNotNull(JdbcUtils.commonDatabaseName(productName)?.takeIf { it.isNotBlank() }) {
                    "Could not detect database type: JDBC metadata returned an empty database product name"
                }

            return resolve(databaseName)
        }

        private fun resolve(databaseName: String): JdbcDatabaseType? =
            when (databaseName.lowercase()) {
                "postgresql" -> PostgreSQL
                "mysql" -> MySQL
                "h2" -> H2
                "mariadb" -> MariaDB
                "microsoft sql server" -> SQLServer
                "oracle" -> Oracle
                else -> null
            }
    }
}
