package io.namastack.outbox

import io.namastack.outbox.OutboxRecordStatus.COMPLETED
import io.namastack.outbox.OutboxRecordStatus.FAILED
import io.namastack.outbox.OutboxRecordStatus.NEW
import io.namastack.outbox.query.PostgresJdbcOutboxRecordQueries
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** Checks auxiliary query access paths against completed history without forcing index scans. */
@Testcontainers
class JdbcPostgresAuxiliaryQueryPlanTest {
    companion object {
        @Container
        @JvmStatic
        val postgres = org.testcontainers.postgresql.PostgreSQLContainer("postgres:17.11-alpine")
    }

    private val queries = PostgresJdbcOutboxRecordQueries("outbox_record")
    private val mapper = JsonMapper.builder().build()
    private lateinit var source: DriverManagerDataSource

    @BeforeEach
    fun setUp() {
        source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        val jdbc = JdbcTemplate(source)
        jdbc.execute("DROP TABLE IF EXISTS outbox_record CASCADE")
        ResourceDatabasePopulator(ClassPathResource("schema/postgres/outbox-tables.sql")).execute(source)
        jdbc.execute(
            """
            INSERT INTO outbox_record
                (id,status,record_key,record_type,payload,created_at,completed_at,
                 failure_count,next_retry_at,partition_no,handler_id)
            SELECT 'history-' || n, 'COMPLETED', 'history-' || n, 'type', 'payload',
                   '2025-12-01'::timestamptz + n * interval '1 millisecond',
                   '2025-12-02'::timestamptz + n * interval '1 millisecond',
                   0, '2025-12-01'::timestamptz, n % 256, 'handler'
            FROM generate_series(1, 50000) n
            """.trimIndent(),
        )
        jdbc.execute(
            """
            INSERT INTO outbox_record
                (id,status,record_key,record_type,payload,created_at,completed_at,
                 failure_count,next_retry_at,partition_no,handler_id)
            SELECT 'pending-' || n, CASE WHEN n <= 1000 THEN 'NEW' ELSE 'FAILED' END,
                   'pending-' || n, 'type', 'payload',
                   '2026-01-01'::timestamptz + n * interval '1 millisecond', NULL,
                   0, '2026-01-01'::timestamptz + n * interval '1 millisecond', n % 256, 'handler'
            FROM generate_series(1, 1200) n
            """.trimIndent(),
        )
        jdbc.execute("VACUUM (ANALYZE) outbox_record")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `counts selective reads deletion and retention have index paths with custom and generic plans`(
        generic: Boolean,
    ) {
        assertIndexed(queries.findStrictRecordKeys, NEW, generic)
        assertIndexed(queries.findNonStrictRecordKeys, NEW, generic)
        assertIndexed(queries.findOldestStrictDueRetry, NEW, generic)
        OutboxRecordStatus.entries.forEach { status ->
            assertIndexed(queries.countByStatus, status, generic)
            assertIndexed(queries.countByPartitionStatus, status, generic)
            assertIndexed(queries.countByPartitionsStatus, status, generic)
        }
        listOf(NEW, FAILED).forEach { status ->
            assertIndexed(queries.findByStatus(status), status, generic)
            assertIndexed(queries.oldestCreatedAtByPartitionsStatus(status), status, generic)
            assertIndexed(queries.findOldestDueRetry(status), status, generic)
            assertIndexed(queries.deleteByStatus(status), status, generic)
        }
        assertIndexed(queries.findByStatusAndNextRetryBefore, FAILED, generic)
        assertIndexed(queries.findIdsByStatusAndNextRetryBefore, FAILED, generic)
        assertIndexed(queries.findByStatusAndCompletedBefore, COMPLETED, generic)
        assertIndexed(queries.findIdsByStatusAndCompletedBefore, COMPLETED, generic)
        assertIndexed(queries.deleteByKeyAndStatus, COMPLETED, generic)
    }

    private fun assertIndexed(
        sql: String,
        status: OutboxRecordStatus,
        generic: Boolean,
    ) {
        val parameters =
            mapOf(
                "status" to ("varchar" to "'${status.name}'"),
                "partitionNo" to ("integer" to "1"),
                "partitions" to ("integer" to "1"),
                "recordKey" to ("varchar" to "'history-42'"),
                "cutoff" to ("timestamptz" to "'2026-01-02'"),
                "now" to ("timestamptz" to "'2026-01-02'"),
                "limit" to ("integer" to "10"),
            )
        val parameterPattern = Regex(":([A-Za-z]+)")
        val names =
            parameterPattern
                .findAll(sql)
                .map { it.groupValues[1] }
                .distinct()
                .toList()
        val statement = parameterPattern.replace(sql) { match -> "\$${names.indexOf(match.groupValues[1]) + 1}" }
        val types = names.joinToString { parameters.getValue(it).first }
        val values = names.joinToString { parameters.getValue(it).second }

        source.connection.use { connection ->
            connection.createStatement().use { jdbc ->
                jdbc.execute("SET plan_cache_mode = ${if (generic) "force_generic_plan" else "force_custom_plan"}")
                jdbc.execute("PREPARE auxiliary_query($types) AS $statement")
                jdbc.executeQuery("EXPLAIN (FORMAT JSON) EXECUTE auxiliary_query($values)").use { result ->
                    assertThat(result.next()).isTrue()
                    val plan = mapper.readTree(result.getString(1))[0]["Plan"]
                    val nodes = planNodes(plan)
                    assertThat(nodes.any { it.path("Node Type").asString().contains("Index") })
                        .describedAs("$sql, status=$status, generic=$generic: $plan")
                        .isTrue()
                    assertThat(nodes.none { it.path("Node Type").asString().contains("Seq Scan") })
                        .describedAs("$sql must not scan completed history: $plan")
                        .isTrue()
                }
            }
        }
    }

    private fun planNodes(plan: JsonNode): List<JsonNode> = listOf(plan) + plan.path("Plans").flatMap { planNodes(it) }
}
