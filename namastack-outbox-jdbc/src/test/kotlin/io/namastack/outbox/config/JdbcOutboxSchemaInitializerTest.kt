package io.namastack.outbox.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.boot.sql.init.DatabaseInitializationMode
import org.springframework.boot.sql.init.DatabaseInitializationSettings
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.init.ScriptStatementFailedException
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

class JdbcOutboxSchemaInitializerTest {
    @ParameterizedTest
    @CsvSource("MySQL,1061", "Microsoft SQL Server,1913", "Microsoft SQL Server,2714", "Oracle,955")
    fun `a concurrent creator does not prevent the complete schema from being installed`(
        product: String,
        code: Int,
    ) {
        val attempts = AtomicInteger()
        val source =
            sourceFailingIndexCreation(attempts) { attempt ->
                if (attempt == 1) {
                    SQLException("duplicate", "42000", code)
                } else {
                    null
                }
            }
        initializer(source, JdbcDatabaseType.from(product)).initializeDatabase()

        assertThat(attempts.get()).isEqualTo(2)
        source.connection.use { connection ->
            connection.metaData.getIndexInfo(null, null, "SCHEMA_RETRY_RECORD", false, false).use { indexes ->
                val names = buildSet { while (indexes.next()) add(indexes.getString("INDEX_NAME")) }
                assertThat(names).contains("IDX_SCHEMA_RETRY_RECORD")
            }
        }
    }

    @Test
    fun `permission errors fail without retrying`() {
        val attempts = AtomicInteger()
        val error = SQLException("permission denied", "42000", 1142)
        val source = sourceFailingIndexCreation(attempts) { error }

        assertThatThrownBy { initializer(source, JdbcDatabaseType.MySQL).initializeDatabase() }
            .isInstanceOf(ScriptStatementFailedException::class.java)
            .hasRootCause(error)
        assertThat(attempts.get()).isEqualTo(1)
    }

    @Test
    fun `a persistent duplicate-object error still fails startup`() {
        val attempts = AtomicInteger()
        val error = SQLException("persistent conflict", "42000", 1061)
        val source = sourceFailingIndexCreation(attempts) { error }

        assertThatThrownBy { initializer(source, JdbcDatabaseType.MySQL).initializeDatabase() }
            .isInstanceOf(ScriptStatementFailedException::class.java)
            .hasRootCause(error)
        assertThat(attempts.get()).isEqualTo(3)
    }

    private fun initializer(
        source: DataSource,
        type: JdbcDatabaseType,
    ): JdbcOutboxSchemaInitializer {
        val settings =
            DatabaseInitializationSettings().apply {
                schemaLocations = listOf("classpath:schema/retry/outbox-index.sql")
                mode = DatabaseInitializationMode.ALWAYS
            }
        return JdbcOutboxSchemaInitializer(source, settings, type)
    }

    private fun sourceFailingIndexCreation(
        attempts: AtomicInteger,
        error: (Int) -> SQLException?,
    ): DataSource {
        val actual = DriverManagerDataSource("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")
        return object : DataSource by actual {
            override fun getConnection(): Connection {
                val connection = actual.connection
                return object : Connection by connection {
                    override fun createStatement(): Statement {
                        val statement = connection.createStatement()
                        return object : Statement by statement {
                            override fun execute(sql: String): Boolean {
                                if (sql.startsWith("CREATE INDEX")) error(attempts.incrementAndGet())?.let { throw it }
                                return statement.execute(sql)
                            }
                        }
                    }
                }
            }
        }
    }
}
