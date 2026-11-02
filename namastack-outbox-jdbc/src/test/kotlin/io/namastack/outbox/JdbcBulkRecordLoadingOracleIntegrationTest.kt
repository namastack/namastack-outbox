package io.namastack.outbox

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.OracleContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import javax.sql.DataSource

@Testcontainers
class JdbcBulkRecordLoadingOracleIntegrationTest : AbstractJdbcBulkRecordLoadingTest() {
    @Autowired
    private lateinit var dataSource: DataSource

    @Test
    fun `schema initialization accepts an equivalent processing index with a custom name`() {
        val jdbc = JdbcTemplate(dataSource)
        jdbc.execute("ALTER INDEX idx_outbox_rec_key_comp_order RENAME TO custom_outbox_head_index")
        try {
            repeat(2) {
                ResourceDatabasePopulator()
                    .apply {
                        setSeparator("/")
                        addScript(ClassPathResource("schema/oracle/outbox-tables.sql"))
                    }.execute(dataSource)
            }
            assertThat(
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM user_indexes WHERE index_name = 'CUSTOM_OUTBOX_HEAD_INDEX'",
                    Int::class.java,
                ),
            ).isEqualTo(1)
            assertThat(
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM user_indexes WHERE index_name = 'IDX_OUTBOX_REC_KEY_COMP_ORDER'",
                    Int::class.java,
                ),
            ).isZero()
        } finally {
            jdbc.execute("ALTER INDEX custom_outbox_head_index RENAME TO idx_outbox_rec_key_comp_order")
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(JdbcBulkRecordLoadingOracleIntegrationTest::class.java)

        @Container
        @JvmStatic
        val oracle: OracleContainer =
            OracleContainer("gvenzl/oracle-xe:18-slim-faststart")
                .withLogConsumer { log.info(it.utf8StringWithoutLineEnding) }

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            if (!oracle.isRunning) {
                oracle.start()
            }

            registry.add("spring.datasource.url") { oracle.jdbcUrl }
            registry.add("spring.datasource.username") { oracle.username }
            registry.add("spring.datasource.password") { oracle.password }
            registry.add("spring.datasource.driver-class-name") { oracle.driverClassName }
        }
    }
}
