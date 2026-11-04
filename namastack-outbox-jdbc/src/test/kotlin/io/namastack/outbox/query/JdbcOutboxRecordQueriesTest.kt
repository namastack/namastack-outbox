package io.namastack.outbox.query

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.JacksonOutboxPayloadSerializer
import io.namastack.outbox.JdbcOutboxPersistenceFactory
import io.namastack.outbox.OutboxRecordStatus
import io.namastack.outbox.OutboxRecordStatus.COMPLETED
import io.namastack.outbox.OutboxRecordStatus.FAILED
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

    private val literalDialects =
        listOf(
            null,
            JdbcDatabaseType.PostgreSQL,
            JdbcDatabaseType.MySQL,
            JdbcDatabaseType.MariaDB,
            JdbcDatabaseType.SQLServer,
            JdbcDatabaseType.H2,
        )

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
    fun `portable statements contain no row limit clause`() {
        val queries = JdbcOutboxRecordQueriesFactory.create(null, table)

        listOf(
            queries.findStrictRecordKeys,
            queries.findNonStrictRecordKeys,
            queries.oldestDueStrict,
            queries.oldestCreatedAtByPartitionsStatus,
            queries.findByStatusAndCompletedBefore,
            queries.findByStatusAndNextRetryBefore,
            queries.findIdsByStatusAndCompletedBefore,
            queries.findIdsByStatusAndNextRetryBefore,
        ).forEach { sql ->
            assertThat(sql).doesNotContain("LIMIT", "FETCH", ":limit", "GROUP BY")
        }
        assertThat(queries.findStrictRecordKeys).endsWith("ORDER BY o.created_at, o.id")
    }

    @Test
    fun `statements carry the literal incomplete condition except on oracle`() {
        literalDialects.map { JdbcOutboxRecordQueriesFactory.create(it, table) }.forEach { queries ->
            assertThat(queries.findStrictRecordKeys).contains("AND o.completed_at IS NULL")
            assertThat(queries.findNonStrictRecordKeys).contains("AND o.completed_at IS NULL")
            assertThat(queries.oldestDueStrict).contains("AND o.completed_at IS NULL")
            assertThat(queries.oldestDueNonStrict).contains("AND o.completed_at IS NULL")
            assertThat(queries.oldestCreatedAtByPartitionsStatus).contains("AND completed_at IS NULL")
            assertThat(queries.findIncompleteByKeyAndStatus).contains("AND completed_at IS NULL")
        }
    }

    @Test
    fun `counts use the literal condition only when every status is incomplete`() {
        literalDialects.map { JdbcOutboxRecordQueriesFactory.create(it, table) }.forEach { queries ->
            listOf(setOf(NEW), setOf(FAILED), setOf(NEW, FAILED)).forEach { statuses ->
                assertThat(queries.countByStatuses(statuses)).contains("completed_at IS NULL")
            }
            listOf(setOf(COMPLETED), setOf(NEW, COMPLETED), OutboxRecordStatus.entries.toSet()).forEach { statuses ->
                assertThat(queries.countByStatuses(statuses)).doesNotContain("completed_at")
            }
            assertThat(queries.countByPartitionsStatus(NEW)).contains("completed_at IS NULL")
            assertThat(queries.countByPartitionsStatus(FAILED)).contains("completed_at IS NULL")
            assertThat(queries.countByPartitionsStatus(COMPLETED)).doesNotContain("completed_at")
        }
    }

    @Test
    fun `postgres statements limit with limit and probe each key for the bulk load`() {
        val queries = JdbcOutboxRecordQueriesFactory.create(JdbcDatabaseType.PostgreSQL, table)

        assertThat(queries.findStrictRecordKeys)
            .contains("earlier.completed_at IS NULL", "earlier.created_at <= o.created_at")
            .endsWith("ORDER BY o.created_at, o.id LIMIT :limit")
        assertThat(queries.findNonStrictRecordKeys)
            .contains("earlier.status = :status AND earlier.next_retry_at <= :now")
            .endsWith("LIMIT :limit")
        assertThat(queries.oldestDueStrict).endsWith("ORDER BY o.next_retry_at LIMIT :limit")
        assertThat(queries.oldestDueNonStrict).startsWith("SELECT MIN(o.next_retry_at)")
        assertThat(queries.oldestCreatedAtByPartitionsStatus).endsWith("ORDER BY created_at LIMIT :limit")
        assertThat(queries.findIncompleteByKeysAndStatus)
            .contains("CROSS JOIN LATERAL", "unnest(CAST(ARRAY[:recordKeys]")
            .contains("ORDER BY r.created_at, r.id")
        assertThat(queries.findIdsByStatusAndCompletedBefore).endsWith("ORDER BY completed_at LIMIT :limit")
    }

    @ParameterizedTest
    @ValueSource(strings = ["MySQL", "MariaDB"])
    fun `mysql and mariadb statements use limit and the in list bulk record load`(database: String) {
        val queries = JdbcOutboxRecordQueriesFactory.create(JdbcDatabaseType.from(database), table)

        assertThat(queries.oldestCreatedAtByPartitionsStatus).endsWith("ORDER BY created_at LIMIT :limit")
        assertThat(queries.findStrictRecordKeys).endsWith("ORDER BY o.created_at, o.id LIMIT :limit")
        assertThat(queries.findIncompleteByKeysAndStatus)
            .contains("record_key IN (:recordKeys) AND status = :status AND completed_at IS NULL")
            .endsWith("ORDER BY record_key, created_at, id")
    }

    @Test
    fun `sql server statements use offset fetch and the portable non strict form`() {
        val queries = JdbcOutboxRecordQueriesFactory.create(JdbcDatabaseType.SQLServer, table)

        assertThat(queries.findStrictRecordKeys)
            .endsWith("ORDER BY o.created_at, o.id OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY")
        assertThat(queries.findNonStrictRecordKeys)
            .doesNotContain("GROUP BY")
            .contains("earlier.status = :status AND earlier.next_retry_at <= :now")
            .endsWith("ORDER BY o.created_at, o.id OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY")
        assertThat(queries.findByStatusAndCompletedBefore)
            .endsWith("ORDER BY completed_at OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY")
    }

    @Test
    fun `h2 statements use fetch first`() {
        val queries = JdbcOutboxRecordQueriesFactory.create(JdbcDatabaseType.H2, table)

        assertThat(queries.findStrictRecordKeys).endsWith("ORDER BY o.created_at, o.id FETCH FIRST :limit ROWS ONLY")
    }

    @Test
    fun `oracle status led statements order by utc instants without an outer incomplete condition`() {
        val queries = JdbcOutboxRecordQueriesFactory.create(JdbcDatabaseType.Oracle, table)

        listOf(queries.findStrictRecordKeys, queries.findNonStrictRecordKeys).forEach { sql ->
            assertThat(sql)
                .doesNotContain("o.completed_at IS NULL AND NOT EXISTS")
                .contains("earlier.completed_at IS NULL")
                .endsWith("ORDER BY SYS_EXTRACT_UTC(o.created_at), o.id FETCH FIRST :limit ROWS ONLY")
        }
        assertThat(queries.oldestDueStrict)
            .startsWith("SELECT /*+ FIRST_ROWS(1) */ o.next_retry_at")
            .contains("earlier.completed_at IS NULL")
            .endsWith("ORDER BY SYS_EXTRACT_UTC(o.next_retry_at) FETCH FIRST :limit ROWS ONLY")
        assertThat(queries.oldestDueNonStrict).doesNotContain("completed_at")
        assertThat(queries.oldestCreatedAtByPartitionsStatus)
            .doesNotContain("completed_at")
            .endsWith("ORDER BY SYS_EXTRACT_UTC(created_at) FETCH FIRST :limit ROWS ONLY")
        OutboxRecordStatus.entries.forEach { status ->
            assertThat(queries.countByStatuses(setOf(status))).doesNotContain("completed_at")
            assertThat(queries.countByPartitionsStatus(status)).doesNotContain("completed_at")
        }
        assertThat(queries.findIdsByStatusAndNextRetryBefore).endsWith("FETCH FIRST :limit ROWS ONLY")
    }

    @Test
    fun `oracle keeps the incomplete condition on key led statements`() {
        val queries = JdbcOutboxRecordQueriesFactory.create(JdbcDatabaseType.Oracle, table)

        assertThat(queries.findIncompleteByKeysAndStatus).contains("AND completed_at IS NULL")
        assertThat(queries.findIncompleteByKeyAndStatus).contains("AND completed_at IS NULL")
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
