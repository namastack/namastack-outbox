package io.namastack.outbox

import io.namastack.outbox.query.PostgresJdbcOutboxRecordQueries
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Instant

/** Checks bounded scan work when completed-only statistics are followed by a pending backlog. */
@Testcontainers
class JdbcPostgresKeySelectionPlanTest {
    companion object {
        @Container
        @JvmStatic
        val postgres = org.testcontainers.postgresql.PostgreSQLContainer("postgres:17.11-alpine")
    }

    private val now = Instant.parse("2026-01-01T12:00:00Z")
    private val queries = PostgresJdbcOutboxRecordQueries("outbox_record")
    private val mapper = JsonMapper.builder().build()
    private lateinit var source: DriverManagerDataSource
    private lateinit var jdbc: JdbcTemplate
    private lateinit var namedJdbc: NamedParameterJdbcTemplate

    @BeforeEach
    fun setUp() {
        source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        jdbc = JdbcTemplate(source)
        namedJdbc = NamedParameterJdbcTemplate(source)
        jdbc.execute("DROP TABLE IF EXISTS outbox_record CASCADE")
        ResourceDatabasePopulator(ClassPathResource("schema/postgres/outbox-tables.sql")).execute(source)
        // Keep the completed-only statistics unchanged until the test explicitly refreshes them.
        jdbc.execute("ALTER TABLE outbox_record SET (autovacuum_enabled = false)")
        insertRecords("history", "COMPLETED", 10000)
        jdbc.execute("ANALYZE outbox_record")
        insertRecords("pending", "NEW", 20000)
    }

    @ParameterizedTest
    @CsvSource("false, false", "false, true", "true, false", "true, true")
    fun `key selection does not rescan the pending working set per candidate`(
        refreshStatistics: Boolean,
        generic: Boolean,
    ) {
        prepareStatistics(refreshStatistics)
        val parameters =
            mapOf(
                "partitions" to listOf(1),
                "status" to "NEW",
                "now" to Timestamp.from(now),
                "limit" to 200,
            )

        listOf(queries.findStrictRecordKeys, queries.findNonStrictRecordKeys).forEach { query ->
            val plan = if (generic) explainGenericKeySelection(query) else explain(query, parameters)
            assertThat(plan["Actual Rows"].asInt()).isEqualTo(200)
            assertNoRepeatedWorkingSetScan(plan)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `bulk loading does not rescan the pending working set per key`(refreshStatistics: Boolean) {
        prepareStatistics(refreshStatistics)
        val keys = (0 until 100).map { "pending-key-${it.toString().padStart(6, '0')}" }
        val plan = explain(queries.findByKeysAndStatus, mapOf("recordKeys" to keys, "status" to "NEW"))

        assertThat(plan["Actual Rows"].asInt()).isEqualTo(1000)
        assertNoRepeatedWorkingSetScan(plan)
    }

    private fun prepareStatistics(refresh: Boolean) {
        if (refresh) {
            jdbc.execute("ANALYZE outbox_record")
        } else {
            assertThat(
                jdbc.queryForObject(
                    "SELECT null_frac FROM pg_stats WHERE schemaname='public' AND tablename='outbox_record' AND attname='completed_at'",
                    Double::class.java,
                ),
            ).isZero()
        }
    }

    private fun explain(
        query: String,
        parameters: Map<String, Any>,
    ): JsonNode {
        val json =
            namedJdbc.queryForObject(
                "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) $query",
                parameters,
                String::class.java,
            )!!
        return mapper.readTree(json)[0]["Plan"]
    }

    /** Uses the same fixture parameters while forcing PostgreSQL's generic prepared plan. */
    private fun explainGenericKeySelection(query: String): JsonNode {
        val statement =
            query
                .replace(":partitions", "\$1")
                .replace(":status", "\$2")
                .replace(":now", "\$3")
                .replace(":limit", "\$4")
        source.connection.use { connection ->
            connection.createStatement().use { jdbc ->
                jdbc.execute("SET plan_cache_mode = force_generic_plan")
                jdbc.execute("PREPARE key_selection(integer,varchar,timestamptz,integer) AS $statement")
                jdbc
                    .executeQuery(
                        "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) EXECUTE key_selection(1,'NEW','$now',200)",
                    ).use { result ->
                        check(result.next())
                        return mapper.readTree(result.getString(1))[0]["Plan"]
                    }
            }
        }
    }

    private fun assertNoRepeatedWorkingSetScan(plan: JsonNode) {
        // Permit parallel scans and indexed point lookups. Reject a scan of the whole pending
        // relation repeated for each outer candidate, even when it happens to use an index.
        val nodes = planNodes(plan)
        val allowedLoops = nodes.maxOf { it.path("Workers Planned").asInt(0) } + 1
        val repeatedScans =
            nodes.filter { node ->
                val keyLookup =
                    listOf(
                        "Index Cond",
                        "Recheck Cond",
                    ).any { node.path(it).asText().contains("record_key") }
                node.path("Relation Name").asText() == "outbox_record" &&
                    node.path("Actual Loops").asInt() > allowedLoops &&
                    !keyLookup
            }
        assertThat(
            repeatedScans,
        ).describedAs("Pending records must not be rescanned for every key or candidate").isEmpty()
    }

    private fun planNodes(plan: JsonNode): List<JsonNode> = listOf(plan) + plan.path("Plans").flatMap { planNodes(it) }

    private fun insertRecords(
        prefix: String,
        status: String,
        count: Int,
    ) {
        jdbc.update(
            """
            INSERT INTO outbox_record
                (id,status,record_key,record_type,payload,created_at,completed_at,
                 failure_count,next_retry_at,partition_no,handler_id)
            SELECT ? || '-' || lpad(n::text,6,'0'), ?, ? || '-key-' || lpad(((n-1)/10)::text,6,'0'),
                   'type','payload',?::timestamptz - interval '1 hour' + n * interval '1 microsecond',
                   CASE WHEN ?='COMPLETED' THEN ?::timestamptz ELSE NULL END,0,?::timestamptz,1,'handler'
            FROM generate_series(1,?) n
            """.trimIndent(),
            prefix,
            status,
            prefix,
            Timestamp.from(now),
            status,
            Timestamp.from(now),
            Timestamp.from(now),
            count,
        )
    }
}
