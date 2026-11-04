package io.namastack.outbox.query

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.JacksonOutboxPayloadSerializer
import io.namastack.outbox.JdbcOutboxPersistenceFactory
import io.namastack.outbox.OutboxRecordStatus.NEW
import io.namastack.outbox.config.JdbcDatabaseType
import org.assertj.core.api.Assertions.assertThat
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import tools.jackson.databind.json.JsonMapper
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.time.Clock
import javax.sql.DataSource

class JdbcOutboxRecordQueriesTest {
    private val table = "outbox_record"
    private val metadata = mockk<DatabaseMetaData>()

    @Test
    fun `selects the statements of each database type`() {
        assertThat(JdbcOutboxRecordQueriesFactory.create(JdbcDatabaseType.PostgreSQL, table))
            .isInstanceOf(PostgresJdbcOutboxRecordQueries::class.java)
        assertThat(JdbcOutboxRecordQueriesFactory.create(JdbcDatabaseType.MySQL, table))
            .isInstanceOf(MySqlJdbcOutboxRecordQueries::class.java)
        assertThat(JdbcOutboxRecordQueriesFactory.create(JdbcDatabaseType.MariaDB, table))
            .isInstanceOf(MariaDbJdbcOutboxRecordQueries::class.java)
        assertThat(JdbcOutboxRecordQueriesFactory.create(JdbcDatabaseType.SQLServer, table))
            .isInstanceOf(SqlServerJdbcOutboxRecordQueries::class.java)
        assertThat(JdbcOutboxRecordQueriesFactory.create(JdbcDatabaseType.Oracle, table))
            .isInstanceOf(OracleJdbcOutboxRecordQueries::class.java)
        assertThat(JdbcOutboxRecordQueriesFactory.create(JdbcDatabaseType.H2, table))
            .isInstanceOf(H2JdbcOutboxRecordQueries::class.java)
        assertThat(JdbcOutboxRecordQueriesFactory.create(null, table).javaClass)
            .isEqualTo(DefaultJdbcOutboxRecordQueries::class.java)
    }

    @Test
    fun `statements use the resolved table name`() {
        val queries = JdbcOutboxRecordQueriesFactory.create(JdbcDatabaseType.PostgreSQL, "\"schema\".\"records\"")

        assertThat(queries.findStrictRecordKeys)
            .contains("FROM \"schema\".\"records\" o", "FROM \"schema\".\"records\" earlier")
        assertThat(queries.findIncompleteByKeysAndStatus).contains("FROM \"schema\".\"records\" r")
    }

    @Test
    fun `detects the database type from the datasource metadata`() {
        val dataSource =
            JdbcDataSource().apply {
                setURL("jdbc:h2:mem:jdbc-outbox-record-queries;DB_CLOSE_DELAY=-1")
                user = "sa"
            }

        assertThat(JdbcDatabaseType.detect(dataSource)).isEqualTo(JdbcDatabaseType.H2)
    }

    @ParameterizedTest
    @ValueSource(strings = ["SQLite", "Apache Derby"])
    fun `unsupported products resolve to no database type`(productName: String) {
        assertThat(JdbcDatabaseType.detect(dataSourceReporting(productName))).isNull()
    }

    @Test
    fun `repository opens no connection at construction and detects the database once on first use`() {
        val dataSource = dataSourceReporting("SQLite")
        val persistence =
            JdbcOutboxPersistenceFactory.create(
                dataSource = dataSource,
                transactionManager = DataSourceTransactionManager(dataSource),
                payloadSerializer = JacksonOutboxPayloadSerializer(JsonMapper.builder().build()),
                clock = Clock.systemUTC(),
            )
        verify(exactly = 0) { dataSource.connection }

        repeat(2) { persistence.recordRepository.countByStatuses(emptySet()) }
        verify(exactly = 0) { dataSource.connection }

        runCatching { persistence.recordRepository.countByStatus(NEW) }
        runCatching { persistence.recordRepository.countByStatus(NEW) }
        verify(exactly = 1) { metadata.databaseProductName }
    }

    private fun dataSourceReporting(productName: String): DataSource {
        every { metadata.databaseProductName } returns productName
        val connection = mockk<Connection>(relaxed = true) { every { metaData } returns metadata }
        return mockk { every { getConnection() } returns connection }
    }
}
