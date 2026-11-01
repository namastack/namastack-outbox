package io.namastack.outbox

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.config.JdbcDatabaseType
import io.namastack.outbox.query.DefaultJdbcOutboxRecordQueries
import io.namastack.outbox.query.H2JdbcOutboxRecordQueries
import io.namastack.outbox.query.JdbcOutboxRecordQueriesFactory
import io.namastack.outbox.query.MariaDbJdbcOutboxRecordQueries
import io.namastack.outbox.query.MySqlJdbcOutboxRecordQueries
import io.namastack.outbox.query.OracleJdbcOutboxRecordQueries
import io.namastack.outbox.query.PostgresJdbcOutboxRecordQueries
import io.namastack.outbox.query.SqlServerJdbcOutboxRecordQueries
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
        assertThat(
            JdbcOutboxRecordQueriesFactory.create(null, table).javaClass,
        ).isEqualTo(DefaultJdbcOutboxRecordQueries::class.java)
    }

    @Test
    fun `portable statements contain no row limit clause`() {
        val queries = JdbcOutboxRecordQueriesFactory.create(null, table)

        listOf(
            queries.findStrictRecordKeys,
            queries.findNonStrictRecordKeys,
            queries.findOldestStrictDueRetry,
            queries.oldestCreatedAtByPartitionsStatus,
            queries.findByStatusAndCompletedBefore,
            queries.findIdsByStatusAndNextRetryBefore,
        ).forEach { sql ->
            assertThat(sql).doesNotContain("LIMIT", "FETCH", ":limit").doesNotContain("GROUP BY")
        }
        assertThat(queries.findStrictRecordKeys).endsWith("ORDER BY o.created_at, o.id")
    }

    @Test
    fun `postgres statements limit the head of key query and probe each key for the bulk load`() {
        val queries = JdbcOutboxRecordQueriesFactory.create(JdbcDatabaseType.PostgreSQL, table)

        assertThat(queries.findStrictRecordKeys)
            .contains(
                "AND o.completed_at IS NULL",
                "earlier.completed_at IS NULL",
                "FETCH FIRST 1 ROW WITH TIES ) earlier",
            ).endsWith("ORDER BY o.created_at, o.id LIMIT :limit")
        assertThat(queries.findNonStrictRecordKeys)
            .contains(
                "earlier.status = :status AND earlier.next_retry_at <= :now",
                "FETCH FIRST 1 ROW WITH TIES ) earlier",
            ).endsWith("LIMIT :limit")
        assertThat(
            queries.findOldestStrictDueRetry,
        ).endsWith("ORDER BY o.next_retry_at LIMIT :limit")
        assertThat(queries.findByKeysAndStatus).contains("CROSS JOIN LATERAL", "unnest(CAST(ARRAY[:recordKeys]")
        assertThat(queries.findIdsByStatusAndCompletedBefore).endsWith("ORDER BY completed_at LIMIT :limit")
        assertThat(queries.oldestCreatedAtByPartitionsStatus)
            .startsWith("SELECT created_at FROM")
            .contains("AND completed_at IS NULL")
            .endsWith("ORDER BY created_at LIMIT :limit")
    }

    @Test
    fun `mysql statements use limit and the in list bulk record load`() {
        val queries = JdbcOutboxRecordQueriesFactory.create(JdbcDatabaseType.MySQL, table)

        assertThat(queries.oldestCreatedAtByPartitionsStatus).endsWith("ORDER BY created_at LIMIT :limit")
        assertThat(queries.findByKeysAndStatus).contains("record_key IN (:recordKeys)", "completed_at IS NULL")
        assertThat(queries.findStrictRecordKeys).endsWith("LIMIT :limit")
    }

    @Test
    fun `sql server statements keep the grouped key query without the previous failure filter`() {
        val queries = JdbcOutboxRecordQueriesFactory.create(JdbcDatabaseType.SQLServer, table)

        assertThat(queries.findStrictRecordKeys)
            .endsWith("ORDER BY o.created_at, o.id OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY")
        assertThat(queries.findNonStrictRecordKeys)
            .contains("GROUP BY o.record_key")
            .endsWith("ORDER BY MIN(o.created_at) ASC")
        assertThat(queries.findByStatusAndCompletedBefore)
            .endsWith("ORDER BY completed_at OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY")
    }

    @Test
    fun `oracle statements order by utc instants and use a status based outer predicate`() {
        val queries = JdbcOutboxRecordQueriesFactory.create(JdbcDatabaseType.Oracle, table)

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
        val queries = JdbcOutboxRecordQueriesFactory.create(JdbcDatabaseType.PostgreSQL, "\"schema\".\"records\"")

        assertThat(queries.findStrictRecordKeys)
            .contains("FROM \"schema\".\"records\" o", "FROM \"schema\".\"records\" key_records")
        assertThat(queries.findByKeysAndStatus).contains("FROM \"schema\".\"records\" key_records")
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
        verify(exactly = 1) { metadata.databaseProductName }
    }

    @Test
    fun `repository detects the database type once on first use`() {
        val dataSource = dataSourceReporting("SQLite")
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

    private val metadata = mockk<DatabaseMetaData>()

    private fun dataSourceReporting(productName: String): DataSource {
        every { metadata.databaseProductName } returns productName
        val connection = mockk<Connection>(relaxed = true) { every { metaData } returns metadata }
        return mockk { every { getConnection() } returns connection }
    }
}
