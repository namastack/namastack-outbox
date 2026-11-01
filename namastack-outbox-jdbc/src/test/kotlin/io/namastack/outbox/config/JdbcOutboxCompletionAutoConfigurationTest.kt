package io.namastack.outbox.config

import io.namastack.outbox.JdbcOutboxCompletionBatcher
import io.namastack.outbox.OutboxJacksonAutoConfiguration
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.OutboxRecordStatus
import org.assertj.core.api.Assertions.assertThat
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

/** Verifies release defaults and preserves datasource routing on synchronous completion writes. */
class JdbcOutboxCompletionAutoConfigurationTest {
    @Test
    fun `default configuration batches completions and commits before returning`() {
        val dataSource = initializedDataSource()
        runner(dataSource).run { context ->
            assertThat(context).hasNotFailed().hasSingleBean(JdbcOutboxCompletionBatcher::class.java)
            val settings = context.getBean<JdbcOutboxConfigurationProperties>().completionBatching
            assertThat(settings.enabled).isTrue()
            assertThat(settings.bulkUpdates).isFalse()
            assertThat(settings.maxBatchSize).isEqualTo(2048)
            assertThat(settings.maxWait).isEqualTo(Duration.ofMillis(1))
            val repository = context.getBean<OutboxRecordRepository>()
            val record = repository.create(newRecord())
            assertThat(repository.updateProcessingState(completed(record))).isTrue()
            assertThat(storedStatus(dataSource, record.id)).isEqualTo("COMPLETED")
        }
    }

    @Test
    fun `batching can be disabled through normal application configuration`() {
        runner(initializedDataSource())
            .withPropertyValues("namastack.outbox.jdbc.completion-batching.enabled=false")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBeansOfType(JdbcOutboxCompletionBatcher::class.java)).isEmpty()
                val repository = context.getBean<OutboxRecordRepository>()
                val record = repository.create(newRecord())
                assertThat(repository.updateProcessingState(completed(record))).isTrue()
            }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `routing datasources keep completion on the caller tenant including through proxies`(wrapped: Boolean) {
        val first = initializedDataSource()
        val second = initializedDataSource()
        val routing = ThreadLocalRoutingDataSource()
        routing.setTargetDataSources(mapOf("first" to first, "second" to second))
        routing.afterPropertiesSet()
        val dataSource = if (wrapped) LazyConnectionDataSourceProxy(routing) else routing
        runner(dataSource).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBeansOfType(JdbcOutboxCompletionBatcher::class.java)).isEmpty()
            val repository = context.getBean<OutboxRecordRepository>()
            val record = repository.create(newRecord())
            routing.currentTenant.set("second")
            repository.create(record)
            assertThat(repository.updateProcessingState(completed(record))).isTrue()
            assertThat(storedStatus(second, record.id)).isEqualTo("COMPLETED")
            assertThat(storedStatus(first, record.id)).isEqualTo("NEW")
            routing.currentTenant.remove()
        }
    }

    private fun runner(dataSource: DataSource) =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    JdbcOutboxAutoConfiguration::class.java,
                    OutboxJacksonAutoConfiguration::class.java,
                ),
            ).withBean(DataSource::class.java, { dataSource })
            .withBean(PlatformTransactionManager::class.java, { DataSourceTransactionManager(dataSource) })

    private fun initializedDataSource(): DataSource =
        JdbcDataSource().apply {
            setURL("jdbc:h2:mem:completion-${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
            ResourceDatabasePopulator(ClassPathResource("schema/h2/outbox-tables.sql")).execute(this)
        }

    private fun storedStatus(
        dataSource: DataSource,
        id: String,
    ): String? =
        JdbcTemplate(dataSource).queryForObject("SELECT status FROM outbox_record WHERE id = ?", String::class.java, id)

    private fun newRecord() =
        OutboxRecord
            .Builder<String>()
            .key("key")
            .payload("payload")
            .handlerId("handler")
            .build(Clock.systemUTC())

    private fun completed(record: OutboxRecord<*>) =
        OutboxRecord.restore(
            id = record.id,
            recordKey = record.key,
            payload = record.payload,
            context = record.context,
            createdAt = record.createdAt,
            status = OutboxRecordStatus.COMPLETED,
            completedAt = Clock.systemUTC().instant(),
            failureCount = record.failureCount,
            failureException = null,
            failureReason = null,
            partition = record.partition,
            nextRetryAt = record.nextRetryAt,
            handlerId = record.handlerId,
        )

    private class ThreadLocalRoutingDataSource : AbstractRoutingDataSource() {
        val currentTenant = ThreadLocal.withInitial { "first" }

        override fun determineCurrentLookupKey(): Any = currentTenant.get()
    }
}
