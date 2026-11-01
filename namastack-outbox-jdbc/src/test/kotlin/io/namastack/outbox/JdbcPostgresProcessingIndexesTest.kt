package io.namastack.outbox

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/** Verifies fresh-install indexes and additive schema upgrades without changing persisted records. */
@Testcontainers
class JdbcPostgresProcessingIndexesTest {
    companion object {
        @Container
        @JvmStatic
        val postgres = org.testcontainers.postgresql.PostgreSQLContainer("postgres:18-alpine")
    }

    private lateinit var source: DriverManagerDataSource
    private lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun setUp() {
        source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        jdbc = JdbcTemplate(source)
        jdbc.execute("DROP TABLE IF EXISTS outbox_record CASCADE")
        apply("schema/postgres/outbox-tables.sql")
    }

    @Test
    fun `fresh schema installs processing indexes without custom statistics`() {
        apply("schema/postgres/outbox-tables.sql")
        assertProcessingIndexes()
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pg_statistic_ext WHERE stxrelid='outbox_record'::regclass",
                Int::class.java,
            ),
        ).isZero()
    }

    @Test
    fun `repeated schema initialization adds processing indexes and preserves legacy indexes and records`() {
        listOf(
            "idx_outbox_record_key_incomplete_ordered",
            "idx_outbox_record_status_retry_incomplete",
            "idx_outbox_record_status_partition",
            "idx_outbox_record_key_status",
            "idx_outbox_record_completed_retention",
        ).forEach { jdbc.execute("DROP INDEX $it") }
        apply("schema/postgres/legacy-processing-indexes.sql")
        jdbc.execute(
            "CREATE INDEX idx_outbox_record_key_incomplete_group ON outbox_record " +
                "(record_key, (completed_at IS NULL) DESC) WHERE completed_at IS NULL",
        )
        jdbc.execute(
            """
            INSERT INTO outbox_record
                (id,status,record_key,record_type,payload,context,created_at,completed_at,
                 failure_count,failure_reason,next_retry_at,partition_no,handler_id)
            VALUES
                ('new','NEW','a','type','original','context',now(),NULL,0,NULL,now(),1,'handler'),
                ('retry','NEW','a','type','original','context',now(),NULL,2,'retry',now()+interval '1 day',1,'handler'),
                ('failed','FAILED','b','type','original','context',now(),NULL,3,'failed',now(),2,'handler'),
                ('complete','COMPLETED','c','type','original','context',now(),now(),0,NULL,now(),3,'handler')
            """.trimIndent(),
        )
        val before = jdbc.queryForList("SELECT * FROM outbox_record ORDER BY id")
        val legacy = indexes()

        repeat(2) {
            apply("schema/postgres/outbox-tables.sql")
            assertThat(indexes()).containsAll(legacy)
            assertThat(indexes()).contains(
                "idx_outbox_record_key_incomplete_ordered",
                "idx_outbox_record_status_retry_incomplete",
                "idx_outbox_record_status_partition",
                "idx_outbox_record_key_status",
                "idx_outbox_record_completed_retention",
            )
            assertThat(jdbc.queryForList("SELECT * FROM outbox_record ORDER BY id")).isEqualTo(before)
        }
    }

    @Test
    fun `status partition and key queries retain index access despite completed history`() {
        jdbc.execute(
            """
            INSERT INTO outbox_record
                (id,status,record_key,record_type,payload,created_at,completed_at,
                 failure_count,next_retry_at,partition_no,handler_id)
            SELECT 'history-' || n, 'COMPLETED', 'key-' || n, 'type', 'payload', now(), now(),
                   0, now(), n % 256, 'handler'
            FROM generate_series(1, 100000) n
            """.trimIndent(),
        )
        jdbc.execute(
            """
            INSERT INTO outbox_record
                (id,status,record_key,record_type,payload,created_at,completed_at,
                 failure_count,next_retry_at,partition_no,handler_id)
            VALUES ('failed','FAILED','failed','type','payload',now(),NULL,1,now()-interval '1 day',1,'handler')
            """.trimIndent(),
        )
        jdbc.execute("VACUUM ANALYZE outbox_record")

        val statements =
            listOf(
                "SELECT * FROM outbox_record WHERE status='FAILED' " +
                    "AND next_retry_at<now() ORDER BY next_retry_at LIMIT 1",
                "SELECT COUNT(*) FROM outbox_record WHERE status='FAILED'",
                "SELECT COUNT(*) FROM outbox_record WHERE partition_no IN (1,2,3) AND status='COMPLETED'",
                "DELETE FROM outbox_record WHERE record_key='key-42' AND status='COMPLETED'",
            )
        statements.forEach { sql ->
            val plan = jdbc.queryForList("EXPLAIN $sql", String::class.java).joinToString("\n")
            assertThat(plan).describedAs(sql).contains("Index").doesNotContain("Seq Scan")
        }
    }

    private fun assertProcessingIndexes() {
        assertThat(indexes()).containsExactlyInAnyOrder(
            "outbox_record_pkey",
            "idx_outbox_record_key_incomplete_ordered",
            "idx_outbox_record_status_retry_incomplete",
            "idx_outbox_record_status_partition",
            "idx_outbox_record_key_status",
            "idx_outbox_record_completed_retention",
        )
    }

    private fun indexes(): List<String> =
        jdbc
            .queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname='public' AND tablename='outbox_record'",
                String::class.java,
            ).filterNotNull()

    private fun apply(path: String) {
        // Apply the same schema resource used by automatic initialization.
        ResourceDatabasePopulator(ClassPathResource(path)).execute(source)
    }
}
