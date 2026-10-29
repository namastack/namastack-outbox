package io.namastack.performance.tooling.jdbc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant

@Testcontainers
class CommittedProductionCountsTest {
    private fun connect() = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private val store by lazy {
        JdbcPerformanceStore(JdbcDatabaseConfig(postgres.jdbcUrl, postgres.username, postgres.password), true)
    }

    @BeforeEach
    fun prepare() {
        connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE TABLE IF NOT EXISTS performance_test_run(run_id text PRIMARY KEY,status " +
                        "text,producer_completed_at timestamptz,produced_records bigint,producer_stop_counts jsonb)",
                )
                statement.execute(
                    "CREATE TABLE IF NOT EXISTS outbox_record(id text PRIMARY KEY,status text,created_at " +
                        "timestamptz,completed_at timestamptz,failure_count integer)",
                )
                statement.execute("TRUNCATE performance_test_run,outbox_record")
                statement.execute("INSERT INTO performance_test_run(run_id,status) VALUES('live','RUNNING')")
                statement.execute(
                    "INSERT INTO outbox_record " +
                        "VALUES('live-record-1','NEW',clock_timestamp(),NULL,0),('live-record-2','NEW',clock_timestamp(),NULL,0),('fixture','COMPLETED','2025-01-01','2025-01-01',0)",
                )
            }
        }
    }

    @Test
    fun `end snapshot excludes completion writes that have not committed`() {
        connect().use { pending ->
            pending.autoCommit = false
            pending.createStatement().use {
                it.execute(
                    "UPDATE outbox_record SET status='COMPLETED',completed_at=clock_timestamp() WHERE " +
                        "starts_with(id,'live-record-')",
                )
            }
            val stop = store.markProduced("live", 2)
            assertEquals(2, store.committedProductionEnd("live")!!.newRecords)
            assertEquals(0, store.committedProductionEnd("live")!!.completedRecords)
            assertTrue(store.hasPendingRecords())
            pending.commit()
            // The old timestamp-only query would incorrectly count these writes at producer stop.
            assertEquals(3, store.statusCountsAt(stop).completedRecords)
            assertEquals(0, store.committedProductionEnd("live")!!.completedRecords)
        }
    }

    @Test
    fun `post run reconstruction excludes retained fixtures and agrees at exact boundaries`() {
        val start = Instant.parse("2026-01-01T00:00:00Z")
        connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "UPDATE outbox_record SET " +
                        "created_at='2026-01-01 00:00:00+00',completed_at='2026-01-01 00:00:15+00',status='COMPLETED' WHERE id='live-record-1'",
                )
                statement.execute(
                    "UPDATE outbox_record SET created_at='2026-01-01 00:00:15+00' WHERE id='live-record-2'",
                )
            }
        }
        val samples = store.productionSamples("live", start, start.plusSeconds(31), Duration.ofSeconds(15))
        assertEquals(listOf(1L, 1L, 1L, 1L), samples.map { it.counts.newRecords })
        assertEquals(listOf(0L, 1L, 1L, 1L), samples.map { it.counts.completedRecords })
        assertEquals(start.plusSeconds(31), samples.last().timestamp)
    }

    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
