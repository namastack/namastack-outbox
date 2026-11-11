package io.namastack.outbox

import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.containers.JdbcDatabaseContainer
import org.testcontainers.containers.OracleContainer
import org.testcontainers.containers.startupcheck.MinimumDurationRunningStartupCheckStrategy
import org.testcontainers.mariadb.MariaDBContainer
import org.testcontainers.mssqlserver.MSSQLServerContainer
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Duration

/**
 * Starts each database once per test JVM. Testcontainers' Ryuk cleans up the containers when the JVM exits.
 *
 * Classes sharing a database must use the same ResourceLock and close their Spring context with DirtiesContext.
 * Tables are dropped before each new context so schema initialization is exercised against an empty database.
 * Do not use @Container: its per-class lifecycle would stop the shared database after the first class.
 */
internal enum class JdbcTestDatabase(
    createContainer: () -> JdbcDatabaseContainer<*>,
) {
    POSTGRES({ PostgreSQLContainer("postgres:18-alpine") }),
    MYSQL({ MySQLContainer("mysql:9.2") }),
    MARIADB({ MariaDBContainer("mariadb:12") }),
    ORACLE({ OracleContainer("gvenzl/oracle-xe:18-slim-faststart") }),
    SQLSERVER({
        MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-latest")
            .withStartupCheckStrategy(MinimumDurationRunningStartupCheckStrategy(Duration.ofSeconds(5)))
            .acceptLicense()
    }),
    ;

    private val container by lazy { createContainer().apply { start() } }

    fun registerProperties(registry: DynamicPropertyRegistry) {
        resetSchema()

        registry.add("spring.datasource.url") { container.jdbcUrl }
        registry.add("spring.datasource.username") { container.username }
        registry.add("spring.datasource.password") { container.password }
        registry.add("spring.datasource.driver-class-name") { container.driverClassName }
    }

    private fun resetSchema() {
        container.createConnection("").use { connection ->
            val metadata = connection.metaData
            val tables =
                metadata.getTables(connection.catalog, connection.schema, "%", arrayOf("TABLE")).use { result ->
                    buildSet {
                        while (result.next()) {
                            add(result.getString("TABLE_NAME").lowercase())
                        }
                    }
                }

            connection.createStatement().use { statement ->
                listOf("outbox_record", "outbox_partition", "outbox_instance")
                    .filter { it in tables }
                    .forEach { statement.executeUpdate("DROP TABLE $it") }
            }
        }
    }
}
