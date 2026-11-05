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
    fun `polling index covers metadata and the complete creation time and id ordering`() {
        val definition =
            JdbcTemplate(dataSource).queryForObject(
                "SELECT pg_get_indexdef('idx_outbox_record_status_created'::regclass)",
                String::class.java,
            )

        assertThat(definition)
            .contains("USING btree (status, created_at, id)")
            .contains("INCLUDE (record_key, partition_no, next_retry_at, completed_at)")
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
