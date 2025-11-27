package io.namastack.outbox

sealed class DatabaseType(
    val schemaLocation: String,
) {
    data object PostgreSQL : DatabaseType(
        schemaLocation = "classpath:schema/postgres/outbox-tables.sql",
    )

    data object MySQL : DatabaseType(
        schemaLocation = "classpath:schema/mysql/outbox-tables.sql",
    )

    data object H2 : DatabaseType(
        schemaLocation = "classpath:schema/h2/outbox-tables.sql",
    )

    data object MariaDB : DatabaseType(
        schemaLocation = "classpath:schema/mariadb/outbox-tables.sql",
    )

    data object SQLServer : DatabaseType(
        schemaLocation = "classpath:schema/sqlserver/outbox-tables.sql",
    )

    companion object {
        fun from(databaseName: String): DatabaseType =
            when (databaseName.lowercase()) {
                "postgresql" -> PostgreSQL
                "mysql" -> MySQL
                "h2" -> H2
                "mariadb" -> MariaDB
                "microsoft sql server" -> SQLServer
                else -> throw IllegalArgumentException("Unsupported database type: $databaseName")
            }
    }
}
