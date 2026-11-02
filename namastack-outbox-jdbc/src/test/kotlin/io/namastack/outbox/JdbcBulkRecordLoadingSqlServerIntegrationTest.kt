package io.namastack.outbox

import io.namastack.outbox.config.JdbcOutboxConfigurationProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.startupcheck.MinimumDurationRunningStartupCheckStrategy
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mssqlserver.MSSQLServerContainer
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource

@Testcontainers
class JdbcBulkRecordLoadingSqlServerIntegrationTest : AbstractJdbcBulkRecordLoadingTest() {
    @Autowired
    private lateinit var dataSource: DataSource

    @Test
    fun `bulk value-table loading preserves unicode keys and payloads in a manually managed schema`() {
        val jdbc = JdbcTemplate(dataSource)
        jdbc.execute("SELECT TOP (0) * INTO unicode_outbox_record FROM outbox_record")
        jdbc.execute("ALTER TABLE unicode_outbox_record ALTER COLUMN record_key NVARCHAR(255) NOT NULL")
        val repository =
            JdbcOutboxPersistenceFactory
                .create(
                    dataSource,
                    DataSourceTransactionManager(dataSource),
                    JacksonOutboxPayloadSerializer(JsonMapper.builder().build()),
                    Clock.systemUTC(),
                    DefaultJdbcTableNameResolver(JdbcOutboxConfigurationProperties(tablePrefix = "unicode_")),
                ).recordRepository
        val now = Instant.now()
        listOf("東京", "??").forEachIndexed { index, key ->
            repository.create(
                OutboxRecord.restore(
                    id = "unicode-$index",
                    recordKey = key,
                    payload = "Grüße aus 東京",
                    context = mapOf("city" to "東京"),
                    partition = 1,
                    createdAt = now,
                    status = OutboxRecordStatus.NEW,
                    completedAt = null,
                    failureCount = 0,
                    failureReason = null,
                    nextRetryAt = now,
                    handlerId = "handler",
                    failureException = null,
                ),
            )
        }
        val pages = repository.findIncompleteRecordPages(setOf("東京", "??", "missing") + (1..8).map { "missing-$it" })
        assertThat(pages.getValue("東京")[0].id).isEqualTo("unicode-0")
        assertThat(pages.getValue("??")[0].id).isEqualTo("unicode-1")
        assertThat(pages.getValue("missing").isEmpty()).isTrue()
        assertThat(repository.findIncompleteRecordPages(setOf("東京")).getValue("東京")[0].id).isEqualTo("unicode-0")
        assertThat(pages.getValue("東京")[0].payload).isEqualTo("Grüße aus 東京")
        assertThat(pages.getValue("東京")[0].context).containsEntry("city", "東京")
    }

    @Test
    fun `additive schema initialization preserves an existing varchar id column and its records`() {
        val jdbc = JdbcTemplate(dataSource)
        val script =
            ClassPathResource("schema/sqlserver/outbox-tables.sql")
                .getContentAsString(Charsets.UTF_8)
                .replace("outbox_", "legacy_outbox_")
        val legacyScript =
            script
                .substringBefore("-- Filtered candidates")
                .replace("id             NVARCHAR(255)", "id             VARCHAR(255)")
        ResourceDatabasePopulator(ByteArrayResource(legacyScript.toByteArray())).execute(dataSource)
        jdbc.execute(
            """
            INSERT INTO legacy_outbox_record
            (id, status, record_key, record_type, payload, created_at, failure_count, next_retry_at, partition_no, handler_id)
            VALUES ('legacy-id', 'NEW', 'legacy-key', 'java.lang.String', '"unchanged"', SYSDATETIME(), 0, SYSDATETIME(), 1, 'handler')
            """.trimIndent(),
        )
        repeat(2) {
            ResourceDatabasePopulator(ByteArrayResource(script.toByteArray())).execute(dataSource)
        }
        assertThat(
            jdbc.queryForObject("SELECT payload FROM legacy_outbox_record WHERE id = 'legacy-id'", String::class.java),
        ).isEqualTo("\"unchanged\"")
        assertThat(
            jdbc.queryForObject(
                "SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'legacy_outbox_record' AND COLUMN_NAME = 'id'",
                String::class.java,
            ),
        ).isEqualTo("varchar")
        assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys.indexes WHERE object_id = OBJECT_ID('legacy_outbox_record') AND name = 'idx_legacy_outbox_record_incomplete_partition'",
                Int::class.java,
            ),
        ).isEqualTo(1)
    }

    companion object {
        private val log = LoggerFactory.getLogger(JdbcBulkRecordLoadingSqlServerIntegrationTest::class.java)

        @Container
        @JvmStatic
        val sqlserver: MSSQLServerContainer =
            MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-latest")
                .withLogConsumer { log.info(it.utf8StringWithoutLineEnding) }
                .withStartupCheckStrategy(MinimumDurationRunningStartupCheckStrategy(Duration.ofSeconds(5)))
                .acceptLicense()

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            if (!sqlserver.isRunning) {
                sqlserver.start()
            }

            registry.add("spring.datasource.url") { sqlserver.jdbcUrl }
            registry.add("spring.datasource.username") { sqlserver.username }
            registry.add("spring.datasource.password") { sqlserver.password }
            registry.add("spring.datasource.driver-class-name") { sqlserver.driverClassName }
        }
    }
}
