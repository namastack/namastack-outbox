package io.namastack.outbox.schema

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mariadb.MariaDBContainer
import javax.sql.DataSource

@Testcontainers
class JdbcSchemaInitializationMariaDbIntegrationTest : AbstractJdbcSchemaInitializationTest() {
    @Autowired
    private lateinit var dataSource: DataSource

    @Test
    fun `outbox record has the shipped secondary indexes`() {
        val indexes =
            JdbcTemplate(dataSource)
                .query(
                    """
                    SELECT index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
                    FROM information_schema.statistics
                    WHERE table_schema = DATABASE() AND table_name = 'outbox_record' AND index_name <> 'PRIMARY'
                    GROUP BY index_name
                    """.trimIndent(),
                    RowMapper { rs, _ -> rs.getString(1) to rs.getString(2) },
                ).toMap()

        assertThat(indexes).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "idx_outbox_record_status_completed_created_id" to
                    "status,completed_at,created_at,id,partition_no,next_retry_at,record_key",
                "idx_outbox_record_key_completed_created_id" to "record_key,completed_at,created_at,id",
                "idx_outbox_record_status_completed_retry" to "status,completed_at,next_retry_at,partition_no",
                "idx_outbox_record_status_partition_created" to "status,partition_no,created_at",
            ),
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(JdbcSchemaInitializationMySqlIntegrationTest::class.java)

        @Container
        @JvmStatic
        val mariadb: MariaDBContainer =
            MariaDBContainer("mariadb:12")
                .withLogConsumer { log.info(it.utf8StringWithoutLineEnding) }

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            if (!mariadb.isRunning) {
                mariadb.start()
            }

            registry.add("spring.datasource.url") { mariadb.jdbcUrl }
            registry.add("spring.datasource.username") { mariadb.username }
            registry.add("spring.datasource.password") { mariadb.password }
            registry.add("spring.datasource.driver-class-name") { mariadb.driverClassName }
        }
    }
}
