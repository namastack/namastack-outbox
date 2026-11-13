package io.namastack.outbox

import io.namastack.outbox.JdbcTestDatabase.POSTGRES
import io.namastack.outbox.query.PostgresJdbcOutboxRecordQueries
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.json.JsonMapper

/**
 * Runs the JDBC repository integration checks against PostgreSQL.
 *
 * @author Aleksander Zamojski
 */
@ResourceLock("jdbc-postgres")
class JdbcOutboxRecordRepositoryPostgresIntegrationTest : AbstractJdbcOutboxRecordRepositoryIntegrationTest() {
    @Autowired
    private lateinit var jdbc: JdbcClient

    @Test
    fun `bulk loading probes keys when statistics contain only completed records`() {
        // A temporary table inherits the real indexes and keeps this fixture and its statistics isolated.
        jdbc.sql("CREATE TEMP TABLE cold_start_records (LIKE outbox_record INCLUDING ALL) ON COMMIT DROP").update()
        seedColdStartRecords("history", "COMPLETED", 200_000)
        jdbc.sql("ANALYZE cold_start_records").update()
        seedColdStartRecords("pending", "NEW", 10_000)
        jdbc.sql("SET LOCAL plan_cache_mode = force_custom_plan").update()

        val query = PostgresJdbcOutboxRecordQueries("cold_start_records").findIncompleteByKeysAndStatus
        val explanation =
            jdbc
                .sql("EXPLAIN (ANALYZE, FORMAT JSON, TIMING OFF) $query")
                .param("recordKeys", (0 until 100).map { "pending-key-$it" })
                .param("status", "NEW")
                .query(String::class.java)
                .single()
        val plan = JsonMapper.builder().build().readTree(explanation)[0]["Plan"]

        assertThat(plan["Actual Rows"].asInt()).isEqualTo(1_000)
        // Check the access condition, not a particular index name or a machine-dependent duration.
        assertThat(plan.findValues("Index Cond").map { it.asString() })
            .anyMatch { it.contains("record_key") }
    }

    private fun seedColdStartRecords(
        prefix: String,
        status: String,
        count: Int,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO cold_start_records
                    (id, status, record_key, record_type, payload, context, created_at, completed_at,
                     failure_count, failure_reason, next_retry_at, partition_no, handler_id)
                SELECT :prefix || '-' || n, :status, :prefix || '-key-' || (n / 10),
                       'test.Payload', repeat('x', 512), NULL, CURRENT_TIMESTAMP,
                       CASE WHEN :status = 'COMPLETED' THEN CURRENT_TIMESTAMP ELSE NULL END,
                       0, NULL, CURRENT_TIMESTAMP, n % 256, 'test.handler'
                FROM generate_series(0, :count - 1) n
                """.trimIndent(),
            ).param("prefix", prefix)
            .param("status", status)
            .param("count", count)
            .update()
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            POSTGRES.registerProperties(registry)
        }
    }
}
