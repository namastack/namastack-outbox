package io.namastack.outbox

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class JdbcOutboxRecordQueriesTest {
    @Test
    fun `queries capture the resolved table name once without database detection`() {
        val resolver = mockk<JdbcTableNameResolver> { every { outboxRecord } returns "custom.records" }
        val queries = JdbcOutboxRecordQueries(resolver)
        every { resolver.outboxRecord } returns "changed.records"

        listOf(true, false).forEach { strict ->
            assertThat(queries.recordKeys(OutboxRecordStatus.NEW, 10, strict))
                .contains("FROM custom.records", "ROW_NUMBER() OVER")
                .doesNotContain("changed.records", "LIMIT", "FETCH", "TOP")
        }
        assertThat(queries.findByKeysAndStatus).contains("FROM custom.records", "completed_at IS NULL")
        verify(exactly = 1) { resolver.outboxRecord }
    }

    @Test
    fun `completed and unlimited key selection retain grouped queries`() {
        val queries = JdbcOutboxRecordQueries(DefaultJdbcTableNameResolver())
        listOf(true, false).forEach { strict ->
            assertThat(queries.recordKeys(OutboxRecordStatus.COMPLETED, 10, strict))
                .contains("GROUP BY o.record_key")
                .doesNotContain("ROW_NUMBER")
            assertThat(queries.recordKeys(OutboxRecordStatus.NEW, 0, strict))
                .contains("GROUP BY o.record_key")
                .doesNotContain("ROW_NUMBER")
        }
    }

    @ParameterizedTest
    @CsvSource(
        "PostgreSQL, FETCH FIRST 100 ROWS ONLY",
        "MySQL, LIMIT 100",
        "MariaDB, LIMIT 100",
        "H2, FETCH FIRST 100 ROWS ONLY",
        "Oracle, FETCH FIRST 100 ROWS ONLY",
        "Microsoft SQL Server, OFFSET 0 ROWS FETCH NEXT 100 ROWS ONLY",
    )
    fun `applies the database limit after eligible key selection`(
        database: String,
        limit: String,
    ) {
        val queries =
            JdbcOutboxRecordQueries(DefaultJdbcTableNameResolver()) {
                JdbcOutboxQueryDialect.from(database)
            }
        listOf(true, false).forEach { strict ->
            assertThat(queries.recordKeys(OutboxRecordStatus.NEW, 100, strict)).endsWith(limit)
            assertThat(queries.recordKeys(OutboxRecordStatus.NEW, 0, strict))
                .contains("GROUP BY o.record_key")
                .doesNotContain("LIMIT", "FETCH", "TOP")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["PostgreSQL", "MariaDB", "Oracle"])
    fun `ordered polling and retry age share a global predecessor barrier`(database: String) {
        val queries =
            JdbcOutboxRecordQueries(DefaultJdbcTableNameResolver()) {
                JdbcOutboxQueryDialect.from(database)
            }
        val selection = queries.recordKeys(OutboxRecordStatus.NEW, 10, true)
        val age = queries.oldestDueRetry(OutboxRecordStatus.NEW, true)
        listOf(selection, age).forEach { sql ->
            assertThat(sql)
                .contains("o.completed_at IS NULL", "AND NOT EXISTS (", "earlier.completed_at IS NULL")
                .contains("earlier.created_at <= o.created_at", "earlier.id < o.id")
                .doesNotContain("earlier.status", "earlier.next_retry_at", "earlier.partition_no")
        }
        val limit = checkNotNull(JdbcOutboxQueryDialect.from(database).limitClause).replace(":limit", "10")
        assertThat(selection).endsWith("ORDER BY o.created_at, o.id $limit").doesNotContain("ROW_NUMBER", "GROUP BY")
        assertThat(age).startsWith("SELECT MIN(o.next_retry_at)").doesNotContain("LIMIT")
        assertThat(queries.recordKeys(OutboxRecordStatus.NEW, 10, false)).contains("ROW_NUMBER() OVER")
    }

    @Test
    fun `distinct key loading resolves global heads before readiness and the outer key limit`() {
        val queries =
            JdbcOutboxRecordQueries(DefaultJdbcTableNameResolver()) {
                JdbcOutboxQueryDialect.from("MySQL")
            }
        val selection = queries.recordKeys(OutboxRecordStatus.NEW, 10, true)
        assertThat(selection)
            .contains("SELECT DISTINCT candidate.record_key", "first_record.completed_at IS NULL")
            .contains("ORDER BY first_record.record_key, first_record.created_at, first_record.id LIMIT 1")
            .contains("JOIN outbox_record head ON head.id = candidate_heads.head_id")
            .doesNotContain("first_record.status", "first_record.next_retry_at", "first_record.partition_no")
            .endsWith("ORDER BY head.created_at, head.id LIMIT 10")
        assertThat(queries.oldestDueRetry(OutboxRecordStatus.NEW, true))
            .startsWith("SELECT MIN(head.next_retry_at)")
            .contains("first_record.completed_at IS NULL")
        assertThat(queries.recordKeys(OutboxRecordStatus.NEW, 10, false)).contains("ROW_NUMBER() OVER")
    }

    @Test
    fun `dialect lookup is lazy and cached while unknown products keep JDBC bounds`() {
        var calls = 0
        val queries =
            JdbcOutboxRecordQueries(DefaultJdbcTableNameResolver()) {
                calls++
                JdbcOutboxQueryDialect.from("Other JDBC Database")
            }
        assertThat(calls).isZero()
        repeat(2) {
            assertThat(queries.recordKeys(OutboxRecordStatus.NEW, 10, true))
                .contains("ROW_NUMBER() OVER")
                .doesNotContain("LIMIT", "FETCH", "TOP")
        }
        assertThat(calls).isEqualTo(1)
    }
}
