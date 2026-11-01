package io.namastack.outbox.config

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.OutboxPayloadSerializer
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.OutboxRecordStatus.NEW
import org.assertj.core.api.Assertions.assertThat
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import java.sql.Connection
import java.sql.DatabaseMetaData
import javax.sql.DataSource

class JdbcOutboxAutoConfigurationTest {
    private val metadata = mockk<DatabaseMetaData>()
    private val jdbcConnection = mockk<Connection>(relaxed = true) { every { metaData } returns metadata }
    private val dataSource = mockk<DataSource> { every { getConnection() } returns jdbcConnection }
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    JdbcOutboxAutoConfiguration::class.java,
                    JdbcOutboxSchemaAutoConfiguration::class.java,
                ),
            ).withBean(DataSource::class.java, { dataSource })
            .withBean(PlatformTransactionManager::class.java, { DataSourceTransactionManager(dataSource) })
            .withBean(OutboxPayloadSerializer::class.java, { mockk() })
            .withPropertyValues("namastack.outbox.jdbc.schema-initialization.enabled=false")

    @Test
    fun `detects the database type during startup without executing a record query`() {
        every { metadata.databaseProductName } returns "H2"

        contextRunner.run { context ->
            assertThat(context).hasNotFailed().hasSingleBean(OutboxRecordRepository::class.java)
            assertThat(context).doesNotHaveBean("outboxDataSourceScriptDatabaseInitializer")
            verify(exactly = 1) { metadata.databaseProductName }
            verify(exactly = 0) { jdbcConnection.prepareStatement(any<String>()) }
        }
    }

    @Test
    fun `unrecognized database uses portable queries when schema initialization is disabled`() {
        every { metadata.databaseProductName } returns "SQLite"

        contextRunner.run { context ->
            assertThat(context).hasNotFailed().hasSingleBean(OutboxRecordRepository::class.java)
            verify(exactly = 1) { metadata.databaseProductName }
        }
    }

    @Test
    fun `unrecognized database still requires manual schema initialization`() {
        every { metadata.databaseProductName } returns "SQLite"

        contextRunner
            .withPropertyValues("namastack.outbox.jdbc.schema-initialization.enabled=true")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasRootCauseInstanceOf(IllegalArgumentException::class.java)
                    .hasStackTraceContaining("Unsupported database type: SQLite")
            }
    }

    @Test
    fun `application datasource is used for client schema initialization and dialect detection`() {
        val source =
            JdbcDataSource().apply {
                setURL("jdbc:h2:mem:outbox-application-datasource;DB_CLOSE_DELAY=-1")
                user = "sa"
            }

        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    JdbcOutboxAutoConfiguration::class.java,
                    JdbcOutboxSchemaAutoConfiguration::class.java,
                ),
            ).withBean(DataSource::class.java, { source })
            .withBean(PlatformTransactionManager::class.java, { DataSourceTransactionManager(source) })
            .withBean(OutboxPayloadSerializer::class.java, { mockk() })
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean("outboxDataSource")
                assertThat(context.getBean(OutboxRecordRepository::class.java).countByStatus(NEW)).isZero()
            }
    }
}
