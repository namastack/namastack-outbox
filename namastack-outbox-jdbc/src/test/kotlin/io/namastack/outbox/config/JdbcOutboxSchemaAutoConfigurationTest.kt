package io.namastack.outbox.config

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import javax.sql.DataSource

class JdbcOutboxSchemaAutoConfigurationTest {
    private val configuration = JdbcOutboxSchemaAutoConfiguration()

    @Nested
    inner class ValidateNoCustomTableNaming {
        @Test
        fun `should throw exception when table prefix is configured`() {
            val dataSource = mock(DataSource::class.java)
            val properties =
                JdbcOutboxConfigurationProperties(
                    tablePrefix = "custom_",
                    schemaInitialization = JdbcOutboxConfigurationProperties.SchemaInitialization(enabled = true),
                )

            assertThatThrownBy {
                configuration.outboxDataSourceScriptDatabaseInitializer(dataSource, properties)
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Cannot use automatic schema initialization")
                .hasMessageContaining("table prefix")
        }

        @Test
        fun `should throw exception when schema name is configured`() {
            val dataSource = mock(DataSource::class.java)
            val properties =
                JdbcOutboxConfigurationProperties(
                    schemaName = "custom_schema",
                    schemaInitialization = JdbcOutboxConfigurationProperties.SchemaInitialization(enabled = true),
                )

            assertThatThrownBy {
                configuration.outboxDataSourceScriptDatabaseInitializer(dataSource, properties)
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Cannot use automatic schema initialization")
                .hasMessageContaining("schema name")
        }

        @Test
        fun `should throw exception when both table prefix and schema name are configured`() {
            val dataSource = mock(DataSource::class.java)
            val properties =
                JdbcOutboxConfigurationProperties(
                    tablePrefix = "my_",
                    schemaName = "my_schema",
                    schemaInitialization = JdbcOutboxConfigurationProperties.SchemaInitialization(enabled = true),
                )

            assertThatThrownBy {
                configuration.outboxDataSourceScriptDatabaseInitializer(dataSource, properties)
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Cannot use automatic schema initialization")
        }

        @Test
        fun `should not throw exception when no custom naming is configured`() {
            val dataSource = mock(DataSource::class.java)
            val properties =
                JdbcOutboxConfigurationProperties(
                    tablePrefix = "",
                    schemaName = null,
                    schemaInitialization = JdbcOutboxConfigurationProperties.SchemaInitialization(enabled = true),
                )

            assertThatThrownBy {
                configuration.outboxDataSourceScriptDatabaseInitializer(dataSource, properties)
            }.isInstanceOf(RuntimeException::class.java)
                .hasMessageContaining("Could not detect database name")
        }
    }
}
