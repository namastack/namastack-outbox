package io.namastack.outbox.schema

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import javax.sql.DataSource

@Testcontainers
class JdbcSchemaInitializationPostgresIntegrationTest : AbstractJdbcSchemaInitializationTest() {
    @Autowired
    private lateinit var dataSource: DataSource

    @Test
    fun `outbox record has the shipped secondary indexes`() {
        val template = JdbcTemplate(dataSource)

        fun definition(index: String) =
            template.queryForObject("SELECT pg_get_indexdef('$index'::regclass)", String::class.java)

        assertThat(definition("idx_outbox_record_status_created_id"))
            .contains("USING btree (status, created_at, id)")
            .contains("INCLUDE (partition_no, next_retry_at, record_key)")
        assertThat(definition("idx_outbox_record_key_completed_created_id"))
            .contains("USING btree (record_key, completed_at, created_at, id)")
        assertThat(definition("idx_outbox_record_status_retry_partition"))
            .contains("USING btree (status, next_retry_at)")
            .contains("INCLUDE (partition_no)")
        assertThat(definition("idx_outbox_record_status_partition"))
            .contains("USING btree (status, partition_no)")
    }

    @Test
    fun `outbox record has no partial index and no extended statistics`() {
        val template = JdbcTemplate(dataSource)

        val partialIndexes =
            template.queryForList(
                """
                SELECT indexname FROM pg_indexes
                WHERE tablename = 'outbox_record'
                  AND indexdef LIKE '% WHERE %'
                """.trimIndent(),
                String::class.java,
            )
        val statistics =
            template.queryForObject(
                "SELECT count(*) FROM pg_statistic_ext WHERE stxrelid = 'outbox_record'::regclass",
                Long::class.java,
            )

        assertThat(partialIndexes).isEmpty()
        assertThat(statistics).isEqualTo(0L)
    }

    companion object {
        private val log = LoggerFactory.getLogger(JdbcSchemaInitializationMySqlIntegrationTest::class.java)

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer =
            PostgreSQLContainer("postgres:18-alpine")
                .withLogConsumer { log.info(it.utf8StringWithoutLineEnding) }

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            if (!postgres.isRunning) {
                postgres.start()
            }

            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.datasource.driver-class-name") { postgres.driverClassName }
        }
    }
}
