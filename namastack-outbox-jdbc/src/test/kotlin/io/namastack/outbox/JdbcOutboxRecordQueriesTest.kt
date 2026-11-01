package io.namastack.outbox

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.config.JdbcDatabaseType
import io.namastack.outbox.query.GenericJdbcOutboxRecordQueries
import io.namastack.outbox.query.H2JdbcOutboxRecordQueries
import io.namastack.outbox.query.JdbcOutboxRecordQueriesFactory
import io.namastack.outbox.query.MariaDbJdbcOutboxRecordQueries
import io.namastack.outbox.query.MySqlJdbcOutboxRecordQueries
import io.namastack.outbox.query.OracleJdbcOutboxRecordQueries
import io.namastack.outbox.query.PostgresJdbcOutboxRecordQueries
import io.namastack.outbox.query.SqlServerJdbcOutboxRecordQueries
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullAndEmptySource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.support.MetaDataAccessException
import tools.jackson.databind.json.JsonMapper
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.SQLException
import java.time.Clock
import javax.sql.DataSource

class JdbcOutboxRecordQueriesTest {
    private val queriesFactory = JdbcOutboxRecordQueriesFactory(DefaultJdbcTableNameResolver())

    @Test
    fun `selects the statements of each database type`() {
        assertThat(queriesFactory.create(JdbcDatabaseType.PostgreSQL))
            .isInstanceOf(PostgresJdbcOutboxRecordQueries::class.java)
        assertThat(queriesFactory.create(JdbcDatabaseType.MySQL))
            .isInstanceOf(MySqlJdbcOutboxRecordQueries::class.java)
        assertThat(queriesFactory.create(JdbcDatabaseType.MariaDB))
            .isInstanceOf(MariaDbJdbcOutboxRecordQueries::class.java)
        assertThat(queriesFactory.create(JdbcDatabaseType.SQLServer))
            .isInstanceOf(SqlServerJdbcOutboxRecordQueries::class.java)
        assertThat(queriesFactory.create(JdbcDatabaseType.Oracle))
            .isInstanceOf(OracleJdbcOutboxRecordQueries::class.java)
        assertThat(queriesFactory.create(JdbcDatabaseType.H2))
            .isInstanceOf(H2JdbcOutboxRecordQueries::class.java)
    }

    @Test
    fun `postgres statements select heads before checking strict eligibility and load requested keys together`() {
        val queries = queriesFactory.create(JdbcDatabaseType.PostgreSQL)

        assertThat(queries.findStrictRecordKeys)
            .contains(
                "SELECT DISTINCT ON (record_key)",
                "WHERE completed_at IS NULL ORDER BY record_key, created_at, id) head",
                "AND head.status = :status AND head.next_retry_at <= :now",
            ).doesNotContain("NOT EXISTS", "WITH TIES")
            .endsWith("ORDER BY head.created_at, head.id LIMIT :limit")
        assertThat(queries.findNonStrictRecordKeys)
            .contains("AND status = :status AND next_retry_at <= :now ORDER BY record_key, created_at, id")
            .endsWith("LIMIT :limit")
        assertThat(queries.findOldestStrictDueRetry).endsWith("ORDER BY head.next_retry_at LIMIT :limit")
        assertThat(queries.findByKeysAndStatus)
            .contains("record_key = ANY(CAST(ARRAY[:recordKeys] AS varchar[]))")
            .doesNotContain("LATERAL")
        assertThat(queries.findIdsByStatusAndCompletedBefore).endsWith("ORDER BY completed_at LIMIT :limit")
        assertThat(queries.oldestCreatedAtByPartitionsStatus(OutboxRecordStatus.NEW))
            .startsWith("SELECT created_at FROM")
            .contains("completed_at IS NULL")
            .endsWith("ORDER BY created_at LIMIT :limit")
    }

    @Test
    fun `mysql statements use limit and the in list bulk record load`() {
        val queries = queriesFactory.create(JdbcDatabaseType.MySQL)

        assertThat(
            queries.oldestCreatedAtByPartitionsStatus(OutboxRecordStatus.NEW),
        ).endsWith("ORDER BY created_at LIMIT :limit")
        assertThat(queries.findByKeysAndStatus).contains("record_key IN (:recordKeys)", "completed_at IS NULL")
        assertThat(queries.findStrictRecordKeys).endsWith("LIMIT :limit")
    }

    @Test
    fun `sql server statements keep the grouped key query without the previous failure filter`() {
        val queries = queriesFactory.create(JdbcDatabaseType.SQLServer)

        assertThat(queries.findStrictRecordKeys)
            .endsWith("ORDER BY o.created_at, o.id OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY")
        assertThat(queries.findNonStrictRecordKeys)
            .contains("GROUP BY o.record_key")
            .endsWith("ORDER BY MIN(o.created_at) ASC OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY")
        assertThat(queries.findByStatusAndCompletedBefore)
            .endsWith("ORDER BY completed_at OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY")
    }

    @Test
    fun `oracle statements order by utc instants and use a status based outer predicate`() {
        val queries = queriesFactory.create(JdbcDatabaseType.Oracle)

        listOf(queries.findStrictRecordKeys, queries.findNonStrictRecordKeys)
            .forEach { sql ->
                assertThat(sql)
                    .doesNotContain("o.completed_at IS NULL")
                    .contains("earlier.completed_at IS NULL")
                    .endsWith("ORDER BY SYS_EXTRACT_UTC(o.created_at), o.id FETCH FIRST :limit ROWS ONLY")
            }
        assertThat(queries.findOldestStrictDueRetry)
            .startsWith("SELECT /*+ FIRST_ROWS(1) */ o.next_retry_at")
            .endsWith("ORDER BY SYS_EXTRACT_UTC(o.next_retry_at) FETCH FIRST :limit ROWS ONLY")
        assertThat(queries.findIdsByStatusAndNextRetryBefore).endsWith("FETCH FIRST :limit ROWS ONLY")
    }

    @Test
    fun `statements use the resolved table name`() {
        val resolver = mockk<JdbcTableNameResolver> { every { outboxRecord } returns "\"schema\".\"records\"" }
        val queries = JdbcOutboxRecordQueriesFactory(resolver).create(JdbcDatabaseType.PostgreSQL)

        assertThat(queries.findStrictRecordKeys)
            .contains("FROM \"schema\".\"records\"")
        assertThat(queries.findByKeysAndStatus).contains("FROM \"schema\".\"records\"")
    }

    @Test
    fun `factory captures the record table namespace once during construction`() {
        val resolver = mockk<JdbcTableNameResolver> { every { outboxRecord } returns "original.records" }
        val factory = JdbcOutboxRecordQueriesFactory(resolver)
        every { resolver.outboxRecord } returns "changed.records"

        listOf(JdbcDatabaseType.PostgreSQL, JdbcDatabaseType.H2).forEach { type ->
            assertThat(factory.create(type).findStrictRecordKeys)
                .contains("FROM original.records")
                .doesNotContain("changed.records")
        }
        verify(exactly = 1) { resolver.outboxRecord }
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
    fun `unrecognized products select the portable fallback`(productName: String) {
        val type = JdbcDatabaseType.detect(dataSourceReporting(productName))
        assertThat(type).isNull()
        assertThat(queriesFactory.create(type)).isInstanceOf(GenericJdbcOutboxRecordQueries::class.java)
        verify(exactly = 1) { metadata.databaseProductName }
    }

    @Test
    fun `repository detects the database type once on first use`() {
        val dataSource = dataSourceReporting("PostgreSQL")
        val persistence =
            JdbcOutboxPersistenceFactory.create(
                dataSource = dataSource,
                transactionManager = DataSourceTransactionManager(dataSource),
                payloadSerializer = JacksonOutboxPayloadSerializer(JsonMapper.builder().build()),
                clock = Clock.systemUTC(),
            )
        verify(exactly = 0) { dataSource.connection }

        repeat(2) { persistence.recordRepository.findIncompleteRecordPages(emptySet()) }
        verify(exactly = 0) { dataSource.connection }

        runCatching { persistence.recordRepository.countByStatus(OutboxRecordStatus.NEW) }
        runCatching { persistence.recordRepository.countByStatus(OutboxRecordStatus.NEW) }
        verify(exactly = 1) { metadata.databaseProductName }
    }

    @Test
    fun `an explicit database type is used without reading the metadata`() {
        val dataSource = dataSourceReporting("PostgreSQL")
        val persistence =
            JdbcOutboxPersistenceFactory.create(
                dataSource = dataSource,
                transactionManager = DataSourceTransactionManager(dataSource),
                payloadSerializer = JacksonOutboxPayloadSerializer(JsonMapper.builder().build()),
                clock = Clock.systemUTC(),
                tableNameResolver = DefaultJdbcTableNameResolver(),
                databaseType = JdbcDatabaseType.H2,
            )

        runCatching { persistence.recordRepository.countByStatus(OutboxRecordStatus.NEW) }
        verify(exactly = 0) { metadata.databaseProductName }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = ["   "])
    fun `missing database product names fail detection`(productName: String?) {
        assertThatThrownBy { JdbcDatabaseType.detect(dataSourceReporting(productName)) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Could not detect database type")
    }

    @Test
    fun `metadata connection failures propagate instead of selecting a default dialect`() {
        val dataSource = mockk<DataSource> { every { connection } throws SQLException("metadata unavailable") }

        assertThatThrownBy { JdbcDatabaseType.detect(dataSource) }
            .isInstanceOf(MetaDataAccessException::class.java)
            .hasRootCauseInstanceOf(SQLException::class.java)
    }

    @Test
    fun `portable queries require JDBC bounds instead of an SQL limit clause`() {
        val queries = queriesFactory.create(null)
        assertThat(queries.usesJdbcRowLimits).isTrue()
        assertThat(queries.findStrictRecordKeys).contains("GROUP BY o.record_key").doesNotContain(":limit")
        assertThat(queries.findNonStrictRecordKeys).contains("GROUP BY o.record_key").doesNotContain(":limit")
        assertThat(queries.findByStatusAndNextRetryBefore).doesNotContain(":limit")
    }

    private val metadata = mockk<DatabaseMetaData>()

    private fun dataSourceReporting(productName: String?): DataSource {
        every { metadata.databaseProductName } returns productName
        val connection = mockk<Connection>(relaxed = true) { every { metaData } returns metadata }
        return mockk { every { getConnection() } returns connection }
    }
}
