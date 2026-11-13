package io.namastack.outbox.config

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.DefaultJdbcTableNameResolver
import io.namastack.outbox.JdbcOutboxRecordRepository
import io.namastack.outbox.JdbcTableNameResolver
import io.namastack.outbox.OutboxProperties
import io.namastack.outbox.completion.OutboxCompletionPersistence
import io.namastack.outbox.completion.OutboxCompletionWriter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.task.SimpleAsyncTaskExecutor
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
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
            .withConfiguration(
                AutoConfigurations.of(
                    TaskExecutionAutoConfiguration::class.java,
                    JdbcOutboxCompletionAutoConfiguration::class.java,
                    OutboxCompletionAutoConfiguration::class.java,
                ),
            )

    @Test
    fun `completion executor is absent by default`() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.containsBean("outboxCompletionExecutor")).isFalse()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `enabled batching uses the configured table and replaces repository completion`(virtualThreads: Boolean) {
        val dataSource =
            DriverManagerDataSource("jdbc:h2:mem:batch-config-${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")
        val jdbc = JdbcTemplate(dataSource)
        jdbc.execute("CREATE TABLE custom_records (id VARCHAR(255) PRIMARY KEY)")
        jdbc.update("INSERT INTO custom_records (id) VALUES (?)", "record")
        val repository = mockk<JdbcOutboxRecordRepository>()

        contextRunner
            .withBean(DataSource::class.java, { dataSource })
            .withBean(
                "outboxJdbcTemplate",
                NamedParameterJdbcTemplate::class.java,
                { NamedParameterJdbcTemplate(dataSource) },
            ).withBean("outboxTransactionTemplate", TransactionTemplate::class.java, {
                TransactionTemplate(DataSourceTransactionManager(dataSource))
            })
            .withBean(JdbcTableNameResolver::class.java, {
                DefaultJdbcTableNameResolver(recordTableName = "custom_records")
            })
            .withBean(JdbcOutboxRecordRepository::class.java, { repository })
            .withPropertyValues(
                "namastack.outbox.completion-batching.enabled=true",
                "spring.threads.virtual.enabled=$virtualThreads",
                "namastack.outbox.completion-batching.max-batch-size=512",
                "namastack.outbox.completion-batching.max-wait=2ms",
            ).run { context ->
                assertThat(context).hasNotFailed()
                if (virtualThreads && Runtime.version().feature() >= 21) {
                    val executor = context.getBean("outboxCompletionExecutor", SimpleAsyncTaskExecutor::class.java)
                    assertThat(executor.concurrencyLimit).isOne()
                } else {
                    val executor = context.getBean("outboxCompletionExecutor", ThreadPoolTaskExecutor::class.java)
                    assertThat(executor.corePoolSize).isOne()
                    assertThat(executor.maxPoolSize).isOne()
                }
                val settings = context.getBean(OutboxProperties::class.java).completionBatching
                assertThat(settings.maxBatchSize).isEqualTo(512)
                assertThat(settings.maxWait).isEqualTo(Duration.ofMillis(2))
                context.getBean(OutboxCompletionPersistence::class.java).deleteCompleted("record")
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM custom_records", Int::class.java)).isZero()
                verify(exactly = 0) { repository.deleteById("record") }
            }
    }

    @Test
    fun `custom repository and writer replace the default implementations`() {
        val repository = mockk<JdbcOutboxRecordRepository>()
        val writer = mockk<OutboxCompletionWriter>()
        every { writer.writeDeletions(any()) } returns listOf(Result.success(Unit))

        contextRunner
            .withBean(JdbcOutboxRecordRepository::class.java, { repository })
            .withBean(OutboxCompletionWriter::class.java, { writer })
            .withPropertyValues("namastack.outbox.completion-batching.enabled=true")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(OutboxCompletionWriter::class.java)
                assertThat(context.getBean(OutboxCompletionWriter::class.java)).isSameAs(writer)
                context.getBean(OutboxCompletionPersistence::class.java).deleteCompleted("record")
                verify(exactly = 1) { writer.writeDeletions(match { it.single().recordId == "record" }) }
                verify(exactly = 0) { repository.deleteById(any()) }
            }
    }

    @Test
    fun `default writer uses the shared template with a non JDBC transaction manager`() {
        val dataSource =
            DriverManagerDataSource("jdbc:h2:mem:completion-shared-${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")
        val jdbc = JdbcTemplate(dataSource)
        jdbc.execute("CREATE TABLE outbox_record (id VARCHAR(255) PRIMARY KEY)")
        jdbc.update("INSERT INTO outbox_record (id) VALUES (?)", "record")
        val transactionStatus = SimpleTransactionStatus()
        val manager = mockk<PlatformTransactionManager>(relaxed = true)
        every { manager.getTransaction(any()) } returns transactionStatus

        contextRunner
            .withBean(
                "outboxJdbcTemplate",
                NamedParameterJdbcTemplate::class.java,
                { NamedParameterJdbcTemplate(dataSource) },
            ).withBean("outboxTransactionTemplate", TransactionTemplate::class.java, { TransactionTemplate(manager) })
            .withBean(JdbcTableNameResolver::class.java, { DefaultJdbcTableNameResolver() })
            .withBean(JdbcOutboxRecordRepository::class.java, { mockk() })
            .withPropertyValues("namastack.outbox.completion-batching.enabled=true")
            .run { context ->
                assertThat(context).hasNotFailed()
                context.getBean(OutboxCompletionPersistence::class.java).deleteCompleted("record")

                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_record", Int::class.java)).isZero()
                verify(exactly = 1) { manager.commit(transactionStatus) }
                verify(exactly = 0) { manager.rollback(any()) }
            }
    }

    @Test
    fun `completion executor is absent when outbox is disabled`() {
        contextRunner
            .withPropertyValues(
                "namastack.outbox.enabled=false",
                "namastack.outbox.completion-batching.enabled=true",
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
                "namastack.outbox.completion-batching.enabled=true",
            ).run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.containsBean("outboxCompletionExecutor")).isFalse()
            }
    }
}
