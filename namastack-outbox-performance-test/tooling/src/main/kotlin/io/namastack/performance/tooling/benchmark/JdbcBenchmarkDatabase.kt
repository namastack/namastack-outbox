package io.namastack.performance.tooling.benchmark

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.namastack.outbox.config.JdbcDatabaseType
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/** Owns the dedicated benchmark pool; schemas always come from the checked-out JDBC module. */
internal class JdbcBenchmarkDatabase(
    val name: String,
    val reportDirectory: Path,
    poolSize: Int = 16,
) : AutoCloseable {
    private val url = checkNotNull(System.getenv("PERF_DB_URL"))
    private val user = System.getenv("PERF_DB_USER") ?: "username"
    private val password = System.getenv("PERF_DB_PASSWORD") ?: "password"
    val mapper = JsonMapper.builder().build()
    private val pool =
        lazy {
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = url
                    username = user
                    this.password = this@JdbcBenchmarkDatabase.password
                    maximumPoolSize = poolSize
                    minimumIdle = 1
                    connectionTimeout = 30_000
                    isAutoCommit = true
                },
            )
        }
    val source: HikariDataSource get() = pool.value

    private val type: JdbcDatabaseType by lazy {
        source.connection.use { JdbcDatabaseType.from(it.metaData.databaseProductName) }
    }

    fun initialize() {
        if (name == "sqlserver") prepareSqlServerDatabase()
        val jdbc = JdbcTemplate(source)
        // Old fixtures must not consume the disposable Oracle Free benchmark user's quota.
        if (name == "oracle") jdbc.execute("PURGE RECYCLEBIN")
        val dropSuffix = if (name == "oracle") " PURGE" else ""
        listOf("outbox_partition", "outbox_instance", "outbox_record").forEach { table ->
            val exists =
                source.connection.use { connection ->
                    connection.metaData
                        .getTables(
                            connection.catalog,
                            null,
                            table.uppercase(),
                            arrayOf("TABLE"),
                        ).use { it.next() } ||
                        connection.metaData
                            .getTables(
                                connection.catalog,
                                null,
                                table,
                                arrayOf("TABLE"),
                            ).use { it.next() }
                }
            if (exists) jdbc.execute("DROP TABLE $table$dropSuffix")
        }
        applySchema()
        write(
            "database.json",
            source.connection.use { connection ->
                mapOf(
                    "name" to name,
                    "product" to connection.metaData.databaseProductName,
                    "version" to connection.metaData.databaseProductVersion,
                    "driver" to connection.metaData.driverName,
                    "driverVersion" to connection.metaData.driverVersion,
                )
            },
        )
    }

    private fun applySchema() {
        ResourceDatabasePopulator()
            .apply {
                setSeparator(type.statementSeparator)
                addScript(ClassPathResource(type.schemaLocation.removePrefix("classpath:")))
            }.execute(source)
    }

    private fun prepareSqlServerDatabase() {
        val adminUrl = url.replace("databaseName=outbox_performance_test", "databaseName=master")
        DriverManager.getConnection(adminUrl, user, password).use { connection ->
            connection.createStatement().use {
                it.execute("IF DB_ID('outbox_performance_test') IS NULL CREATE DATABASE outbox_performance_test")
                it.execute(
                    "ALTER DATABASE outbox_performance_test SET READ_COMMITTED_SNAPSHOT ON WITH ROLLBACK IMMEDIATE",
                )
            }
        }
    }

    fun analyze() {
        val sql =
            when (name) {
                "postgres" -> "ANALYZE outbox_record"
                "mysql", "mariadb" -> "ANALYZE TABLE outbox_record"
                "sqlserver" -> "UPDATE STATISTICS outbox_record WITH FULLSCAN"
                "oracle" -> "BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, 'OUTBOX_RECORD', cascade => TRUE); END;"
                "h2" -> "ANALYZE"
                else -> error("Unknown database: $name")
            }
        source.connection.use { it.createStatement().use { statement -> statement.execute(sql) } }
    }

    fun connection(): Connection = source.connection

    fun write(
        file: String,
        value: Any,
    ) {
        reportDirectory.toFile().mkdirs()
        mapper.writerWithDefaultPrettyPrinter().writeValue(reportDirectory.resolve(file).toFile(), value)
    }

    override fun close() {
        if (pool.isInitialized()) source.close()
    }
}
