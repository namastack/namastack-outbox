package io.namastack.outbox.config

import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.DefaultJdbcTableNameResolver
import io.namastack.outbox.JdbcOutboxRecordRepository
import io.namastack.outbox.JdbcTableNameResolver
import io.namastack.outbox.OutboxProperties
import io.namastack.outbox.completion.OutboxCompletionPersistence
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

/**
 * Tests optional JDBC completion batching configuration.
 *
 * @author Roland Beisel
 */
class JdbcOutboxCompletionAutoConfigurationTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JdbcOutboxCompletionAutoConfiguration::class.java))

    @Test
    fun `completion executor is absent by default`() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.containsBean("outboxCompletionExecutor")).isFalse()
        }
    }

    @Test
    fun `enabled batching uses the configured table and replaces repository completion`() {
        val dataSource =
            DriverManagerDataSource("jdbc:h2:mem:batch-config-${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")
        val jdbc = JdbcTemplate(dataSource)
        jdbc.execute("CREATE TABLE custom_records (id VARCHAR(255) PRIMARY KEY)")
        jdbc.update("INSERT INTO custom_records (id) VALUES (?)", "record")
        val repository = mockk<JdbcOutboxRecordRepository>()

        contextRunner
            .withBean(DataSource::class.java, { dataSource })
            .withBean("outboxJdbcClient", JdbcClient::class.java, { JdbcClient.create(dataSource) })
            .withBean("outboxTransactionTemplate", TransactionTemplate::class.java, {
                TransactionTemplate(DataSourceTransactionManager(dataSource))
            })
            .withBean(JdbcTableNameResolver::class.java, {
                DefaultJdbcTableNameResolver(recordTableName = "custom_records")
            })
            .withBean(OutboxProperties::class.java, { OutboxProperties() })
            .withBean(JdbcOutboxRecordRepository::class.java, { repository })
            .withPropertyValues(
                "namastack.outbox.jdbc.completion-batching.enabled=true",
                "namastack.outbox.jdbc.completion-batching.writer-count=3",
                "namastack.outbox.jdbc.completion-batching.max-batch-size=512",
                "namastack.outbox.jdbc.completion-batching.max-wait=2ms",
            ).run { context ->
                assertThat(context).hasNotFailed()
                val executor = context.getBean("outboxCompletionExecutor", ThreadPoolTaskExecutor::class.java)
                assertThat(executor.corePoolSize).isEqualTo(3)
                assertThat(executor.maxPoolSize).isEqualTo(3)
                val settings = context.getBean(JdbcOutboxConfigurationProperties::class.java).completionBatching
                assertThat(settings.maxBatchSize).isEqualTo(512)
                assertThat(settings.maxWait).isEqualTo(Duration.ofMillis(2))
                context.getBean(OutboxCompletionPersistence::class.java).deleteCompleted("record")
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM custom_records", Int::class.java)).isZero()
                verify(exactly = 0) { repository.deleteById("record") }
            }
    }

    @Test
    fun `completion executor is absent when outbox is disabled`() {
        contextRunner
            .withPropertyValues(
                "namastack.outbox.enabled=false",
                "namastack.outbox.jdbc.completion-batching.enabled=true",
            ).run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.containsBean("outboxCompletionExecutor")).isFalse()
            }
    }

    @Test
    fun `completion executor is absent in channels mode`() {
        contextRunner
            .withPropertyValues(
                "namastack.outbox.mode=channels",
                "namastack.outbox.jdbc.completion-batching.enabled=true",
            ).run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.containsBean("outboxCompletionExecutor")).isFalse()
            }
    }
}
