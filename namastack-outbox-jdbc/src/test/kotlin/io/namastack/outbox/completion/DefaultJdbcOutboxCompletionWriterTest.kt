package io.namastack.outbox.completion

import io.mockk.every
import io.mockk.mockk
import io.namastack.outbox.OutboxRecordStatus.COMPLETED
import io.namastack.outbox.query.H2JdbcOutboxRecordQueries
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.namedparam.SqlParameterSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

class DefaultJdbcOutboxCompletionWriterTest {
    private val now = Instant.parse("2026-01-01T12:00:00Z")
    private lateinit var jdbc: JdbcTemplate
    private lateinit var transactions: TransactionTemplate
    private lateinit var writer: DefaultJdbcOutboxCompletionWriter

    @BeforeEach
    fun setUp() {
        val dataSource =
            DriverManagerDataSource(
                "jdbc:h2:mem:completion-${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
                "sa",
                "",
            )
        jdbc = JdbcTemplate(dataSource)
        transactions = TransactionTemplate(DataSourceTransactionManager(dataSource))
        jdbc.execute(
            """
            CREATE TABLE outbox_record (
                id VARCHAR(255) PRIMARY KEY,
                status VARCHAR(20) NOT NULL,
                completed_at TIMESTAMP WITH TIME ZONE,
                failure_count INT NOT NULL CHECK (failure_count >= 0),
                failure_reason VARCHAR(1000),
                next_retry_at TIMESTAMP WITH TIME ZONE NOT NULL
            )
            """.trimIndent(),
        )
        writer =
            DefaultJdbcOutboxCompletionWriter(
                NamedParameterJdbcTemplate(dataSource),
                transactions,
            ) { H2JdbcOutboxRecordQueries("outbox_record") }
    }

    @Test
    fun `updates records in a committed batch`() {
        insert("updated-1")
        insert("updated-2")

        val results =
            writer.writeUpdates(
                listOf(
                    update("updated-1"),
                    update("updated-2"),
                ),
            )

        assertThat(results).allMatch { it.isSuccess }
        assertThat(status("updated-1")).isEqualTo("COMPLETED")
        assertThat(status("updated-2")).isEqualTo("COMPLETED")
    }

    @Test
    fun `deletes records in a committed batch`() {
        insert("deleted-1")
        insert("deleted-2")

        val results =
            writer.writeDeletions(
                listOf(
                    JdbcOutboxCompletion.Delete("deleted-1"),
                    JdbcOutboxCompletion.Delete("deleted-2"),
                ),
            )

        assertThat(results).allMatch { it.isSuccess }
        assertThat(count("deleted-1")).isZero()
        assertThat(count("deleted-2")).isZero()
    }

    @Test
    fun `rolls back a failed batch and isolates the failing record`() {
        insert("first")
        insert("invalid")
        insert("last")

        val results =
            writer.writeUpdates(
                listOf(
                    update("first"),
                    update("invalid", failureCount = -1),
                    update("last"),
                ),
            )

        assertThat(results.map { it.isSuccess }).containsExactly(true, false, true)
        assertThat(results[1].exceptionOrNull()).isInstanceOf(DataIntegrityViolationException::class.java)
        assertThat(status("first")).isEqualTo("COMPLETED")
        assertThat(status("invalid")).isEqualTo("NEW")
        assertThat(status("last")).isEqualTo("COMPLETED")
    }

    @Test
    fun `rejects writes inside a caller transaction`() {
        insert("record")

        transactions.executeWithoutResult {
            assertThatThrownBy { writer.writeUpdates(listOf(update("record"))) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("outside an existing transaction")
        }
        assertThat(status("record")).isEqualTo("NEW")
    }

    @Test
    fun `accepts a successful batch without exact row counts`() {
        val batchJdbc = mockk<NamedParameterJdbcTemplate>()
        every { batchJdbc.batchUpdate(any(), any<Array<SqlParameterSource>>()) } returns
            intArrayOf(Statement.SUCCESS_NO_INFO)
        val batchWriter =
            DefaultJdbcOutboxCompletionWriter(batchJdbc, transactions) {
                H2JdbcOutboxRecordQueries("outbox_record")
            }

        val result = batchWriter.writeUpdates(listOf(update("record")))

        assertThat(result.single().isSuccess).isTrue()
    }

    @Test
    fun `does not report success for an unknown negative batch result`() {
        val batchJdbc = mockk<NamedParameterJdbcTemplate>()
        every { batchJdbc.batchUpdate(any(), any<Array<SqlParameterSource>>()) } returns intArrayOf(-4)
        val batchWriter =
            DefaultJdbcOutboxCompletionWriter(batchJdbc, transactions) {
                H2JdbcOutboxRecordQueries("outbox_record")
            }

        val result = batchWriter.writeUpdates(listOf(update("record")))

        assertThat(result.single().isFailure).isTrue()
    }

    private fun update(
        id: String,
        failureCount: Int = 0,
    ) = JdbcOutboxCompletion.Update(id, COMPLETED, now, failureCount, null, now)

    private fun insert(id: String) {
        jdbc.update(
            """
            INSERT INTO outbox_record (id, status, completed_at, failure_count, failure_reason, next_retry_at)
            VALUES (?, 'NEW', NULL, 0, NULL, ?)
            """.trimIndent(),
            id,
            Timestamp.from(now),
        )
    }

    private fun status(id: String): String? =
        jdbc.queryForObject("SELECT status FROM outbox_record WHERE id = ?", String::class.java, id)

    private fun count(id: String): Int? =
        jdbc.queryForObject("SELECT COUNT(*) FROM outbox_record WHERE id = ?", Int::class.java, id)
}
