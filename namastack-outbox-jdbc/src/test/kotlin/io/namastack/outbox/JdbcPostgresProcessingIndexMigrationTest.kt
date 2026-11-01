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

/** Verifies fresh-install indexes and the staged upgrade without changing persisted records. */
@Testcontainers
class JdbcPostgresProcessingIndexMigrationTest {
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
    fun `fresh schema installs only the primary key and two processing indexes`() {
        apply("schema/postgres/outbox-tables.sql")
        assertProcessingIndexes()
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pg_statistic_ext WHERE stxname='outbox_record_status_completed_stx'",
                Int::class.java,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `staged migration preserves records and keeps legacy indexes until the second phase`() {
        jdbc.execute("DROP INDEX idx_outbox_record_key_incomplete_group")
        jdbc.execute("DROP INDEX idx_outbox_record_created_id_incomplete")
        jdbc.execute("DROP STATISTICS outbox_record_status_completed_stx")
        apply("schema/postgres/legacy-processing-indexes.sql")
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

        apply("schema/postgres/migrations/01_add_processing_indexes.sql")
        apply("schema/postgres/migrations/01_add_processing_indexes.sql")
        assertThat(indexes()).containsAll(legacy)
        assertThat(jdbc.queryForList("SELECT * FROM outbox_record ORDER BY id")).isEqualTo(before)

        apply("schema/postgres/migrations/02_drop_legacy_processing_indexes.sql")
        apply("schema/postgres/migrations/02_drop_legacy_processing_indexes.sql")
        assertProcessingIndexes()
        assertThat(jdbc.queryForList("SELECT * FROM outbox_record ORDER BY id")).isEqualTo(before)
    }

    private fun assertProcessingIndexes() {
        assertThat(indexes()).containsExactlyInAnyOrder(
            "outbox_record_pkey",
            "idx_outbox_record_key_incomplete_group",
            "idx_outbox_record_created_id_incomplete",
        )
    }

    private fun indexes(): List<String> =
        jdbc
            .queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname='public' AND tablename='outbox_record'",
                String::class.java,
            ).filterNotNull()

    private fun apply(path: String) {
        // Concurrent index creation must run outside an enclosing transaction.
        ResourceDatabasePopulator(ClassPathResource(path)).execute(source)
    }
}
