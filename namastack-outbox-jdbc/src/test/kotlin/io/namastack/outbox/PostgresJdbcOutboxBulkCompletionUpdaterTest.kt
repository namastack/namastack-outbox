package io.namastack.outbox

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException

@Testcontainers
class PostgresJdbcOutboxBulkCompletionUpdaterTest {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
    }

    private lateinit var jdbc: JdbcTemplate
    private lateinit var transactions: TransactionTemplate
    private lateinit var dataSource: DriverManagerDataSource

    @BeforeEach
    fun setUp() {
        dataSource =
            object : DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password) {
                override fun getConnection(): Connection =
                    super.getConnection().apply {
                        createStatement().use { it.execute("SET TIME ZONE 'Pacific/Kiritimati'") }
                    }
            }
        jdbc = JdbcTemplate(dataSource)
        transactions = TransactionTemplate(DataSourceTransactionManager(dataSource))
        jdbc.execute("DROP TABLE IF EXISTS completion_test")
        jdbc.execute(
            """
            CREATE TABLE completion_test (
                id varchar PRIMARY KEY, status varchar NOT NULL DEFAULT 'NEW', completed_at timestamptz,
                failure_count integer NOT NULL DEFAULT 0 CHECK (failure_count >= 0), failure_reason varchar,
                next_retry_at timestamptz NOT NULL DEFAULT now(), payload varchar NOT NULL DEFAULT 'original'
            )
            """.trimIndent(),
        )
    }

    private fun completion(
        id: String,
        failures: Int = 3,
        reason: String? = null,
        completedAt: Instant? = Instant.parse("2026-03-29T01:59:59.123456Z"),
        nextRetryAt: Instant = Instant.parse("2026-10-25T01:59:59.987654Z"),
    ) = JdbcOutboxCompletion(
        OutboxRecord.restore(
            id = id,
            recordKey = id,
            payload = "must not be written",
            context = emptyMap(),
            createdAt = Instant.EPOCH,
            status = OutboxRecordStatus.COMPLETED,
            completedAt = completedAt,
            failureCount = failures,
            failureException = null,
            failureReason = reason,
            partition = 0,
            nextRetryAt = nextRetryAt,
            handlerId = "handler",
        ),
    )

    private fun insert(vararg ids: String) {
        ids.forEach { jdbc.update("INSERT INTO completion_test (id) VALUES (?)", it) }
    }

    private fun writer(template: TransactionTemplate = transactions) =
        JdbcOutboxCompletionWriter(jdbc, template, "completion_test") {
            PostgresJdbcOutboxBulkCompletionUpdater(jdbc, "completion_test")
        }

    @Test
    fun `array timestamps use the same microsecond rounding as JDBC timestamps`() {
        val instants =
            listOf(
                Instant.parse("2026-03-29T01:59:59.123456500Z"),
                Instant.parse("2026-03-29T01:59:59.123457500Z"),
                Instant.parse("2026-03-29T01:59:59.999999500Z"),
                Instant.parse("1999-12-31T23:59:59.123456500Z"),
                Instant.parse("1999-12-31T23:59:59.999999500Z"),
                Instant.parse("2000-01-01T00:00:00.000000499Z"),
                Instant.parse("1969-12-31T23:59:59.123456500Z"),
                Instant.parse("1800-01-01T12:34:56.123456500Z"),
                Instant.parse("2500-01-01T12:34:56.123456500Z"),
                Instant.parse("+250000-01-01T12:34:56.123456500Z"),
            )
        instants.forEachIndexed { index, instant ->
            val jdbcId = "jdbc-$index"
            val bulkId = "bulk-$index"
            insert(jdbcId, bulkId)
            val jdbcRequest = completion(jdbcId, completedAt = instant, nextRetryAt = instant)
            val bulkRequest = completion(bulkId, completedAt = instant, nextRetryAt = instant)
            JdbcOutboxCompletionWriter(jdbc, transactions, "completion_test").write(listOf(jdbcRequest))
            writer().write(listOf(bulkRequest))
            assertThat(jdbcRequest.awaitCommit()).isTrue()
            assertThat(bulkRequest.awaitCommit()).isTrue()
            val expected =
                jdbc.queryForMap(
                    "SELECT completed_at, next_retry_at FROM completion_test WHERE id = ?",
                    jdbcId,
                )
            val actual =
                jdbc.queryForMap(
                    "SELECT completed_at, next_retry_at FROM completion_test WHERE id = ?",
                    bulkId,
                )
            for (column in listOf("completed_at", "next_retry_at")) {
                assertThat((actual[column] as java.sql.Timestamp).toInstant())
                    .describedAs("%s for %s", column, instant)
                    .isEqualTo((expected[column] as java.sql.Timestamp).toInstant())
            }
        }
    }

    @Test
    fun `returned ordinals preserve exact results for missing first middle and last records`() {
        insert("b", "d")
        val requests = listOf("e", "b", "c", "d", "a").map { completion(it) }
        writer().write(requests)
        assertThat(requests.map { it.awaitCommit() }).containsExactly(false, true, false, true, false)
        assertThat(jdbc.queryForList("SELECT payload FROM completion_test", String::class.java))
            .containsOnly("original")
    }

    @Test
    fun `arrays preserve nulls unicode quoting metadata and UTC instants in a different session timezone`() {
        insert("a'🌍", "b")
        val requests = listOf(completion("a'🌍", reason = "can't \"retry\" 🌍"), completion("b", completedAt = null))
        writer().write(requests)
        assertThat(requests.map { it.awaitCommit() }).containsOnly(true)
        assertThat(jdbc.queryForObject("SHOW TimeZone", String::class.java)).isEqualTo("Pacific/Kiritimati")
        val rows = jdbc.queryForList("SELECT * FROM completion_test ORDER BY id")
        assertThat(rows[0]["failure_reason"]).isEqualTo("can't \"retry\" 🌍")
        assertThat((rows[0]["completed_at"] as java.sql.Timestamp).toInstant()).isEqualTo(requests[0].completedAt)
        assertThat((rows[0]["next_retry_at"] as java.sql.Timestamp).toInstant()).isEqualTo(requests[0].nextRetryAt)
        assertThat(rows[0]["failure_count"]).isEqualTo(3)
        assertThat(rows[1]["completed_at"]).isNull()
        assertThat(rows[1]["failure_reason"]).isNull()
    }

    @Test
    fun `constraint failure rolls back the bulk statement before isolating poisoned updates`() {
        insert("a", "b", "c")
        val requests = listOf(completion("a"), completion("b", failures = -1), completion("c"))
        writer().write(requests)
        assertThat(requests[0].awaitCommit()).isTrue()
        assertThatThrownBy { requests[1].awaitCommit() }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThat(requests[2].awaitCommit()).isTrue()
        assertThat(jdbc.queryForObject("SELECT status FROM completion_test WHERE id='b'", String::class.java))
            .isEqualTo("NEW")
    }

    @Test
    fun `duplicate ids fall back to JDBC statements rather than collapsing updates`() {
        insert("same")
        val requests = listOf(completion("same", failures = 1), completion("same", failures = 2))
        writer().write(requests)
        assertThat(requests.map { it.awaitCommit() }).containsOnly(true)
        assertThat(jdbc.queryForObject("SELECT failure_count FROM completion_test", Int::class.java)).isEqualTo(2)
    }

    @Test
    fun `bulk update results remain unacknowledged until the transaction commits`() {
        insert("a", "b")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val manager =
            object : DataSourceTransactionManager(dataSource) {
                override fun doCommit(status: DefaultTransactionStatus) {
                    entered.countDown()
                    check(release.await(10, SECONDS))
                    super.doCommit(status)
                }
            }
        val requests = listOf(completion("a"), completion("b"))
        val executor = Executors.newFixedThreadPool(2)
        try {
            val writing = executor.submit { writer(TransactionTemplate(manager)).write(requests) }
            assertThat(entered.await(10, SECONDS)).isTrue()
            val acknowledgement = executor.submit<Boolean> { requests[0].awaitCommit() }
            assertThatThrownBy { acknowledgement.get(100, MILLISECONDS) }.isInstanceOf(TimeoutException::class.java)
            assertThat(jdbc.queryForList("SELECT status FROM completion_test", String::class.java)).containsOnly("NEW")
            release.countDown()
            writing.get(10, SECONDS)
            assertThat(acknowledgement.get(10, SECONDS)).isTrue()
            assertThat(requests[1].awaitCommit()).isTrue()
            assertThat(
                jdbc.queryForList("SELECT status FROM completion_test", String::class.java),
            ).containsOnly("COMPLETED")
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `uncertain commit failure propagates without replaying the statement`() {
        insert("a", "b")
        jdbc.execute("CREATE TABLE update_audit (id varchar)")
        jdbc.execute(
            """
            CREATE OR REPLACE FUNCTION audit_completion_update() RETURNS trigger LANGUAGE plpgsql AS
            'BEGIN INSERT INTO update_audit VALUES (NEW.id); RETURN NEW; END;'
            """.trimIndent(),
        )
        jdbc.execute(
            "CREATE TRIGGER completion_audit AFTER UPDATE ON completion_test FOR EACH ROW EXECUTE FUNCTION audit_completion_update()",
        )
        val manager =
            object : DataSourceTransactionManager(dataSource) {
                override fun doCommit(status: DefaultTransactionStatus) {
                    super.doCommit(status)
                    throw TransactionSystemException("Simulated lost commit acknowledgement")
                }
            }
        try {
            assertThatThrownBy { writer(TransactionTemplate(manager)).write(listOf(completion("a"), completion("b"))) }
                .isInstanceOf(TransactionSystemException::class.java)
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM update_audit", Long::class.java)).isEqualTo(2)
        } finally {
            jdbc.execute("DROP TABLE update_audit")
        }
    }
}
