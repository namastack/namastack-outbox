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
    fun `incomplete record indexes have the shipped columns and the same predicate`() {
        val template = JdbcTemplate(dataSource)

        fun definition(index: String) =
            template.queryForObject("SELECT pg_get_indexdef('$index'::regclass)", String::class.java)

        assertThat(definition("idx_outbox_record_status_created_id_incomplete"))
            .contains("USING btree (status, created_at, id)")
            .contains("INCLUDE (partition_no, next_retry_at, record_key)")
            .contains("WHERE (completed_at IS NULL)")
        assertThat(definition("idx_outbox_record_key_created_id_incomplete"))
            .contains("USING btree (record_key, created_at, id)")
            .contains("WHERE (completed_at IS NULL)")
        assertThat(definition("idx_outbox_record_status_retry_incomplete"))
            .contains("USING btree (status, next_retry_at)")
            .contains("INCLUDE (partition_no)")
            .contains("WHERE (completed_at IS NULL)")
        assertThat(definition("idx_outbox_record_status_partition_incomplete"))
            .contains("USING btree (status, partition_no)")
            .contains("WHERE (completed_at IS NULL)")
    }

    @Test
    fun `outbox record has no full secondary index and has the extended statistics`() {
        val template = JdbcTemplate(dataSource)

        val fullIndexes =
            template.queryForList(
                """
                SELECT indexname FROM pg_indexes
                WHERE tablename = 'outbox_record'
                  AND indexname <> 'outbox_record_pkey'
                  AND indexdef NOT LIKE '%WHERE (completed_at IS NULL)'
                """.trimIndent(),
                String::class.java,
            )
        val statistics =
            template.queryForObject(
                "SELECT count(*) FROM pg_statistic_ext WHERE stxname = 'outbox_record_status_completed_stx'",
                Long::class.java,
            )

        assertThat(fullIndexes).isEmpty()
        assertThat(statistics).isEqualTo(1L)
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
