package io.namastack.outbox.config

import io.namastack.outbox.JdbcTableNameResolver
import io.namastack.outbox.OutboxProperties
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.completion.JdbcOutboxCompletionBatchWriter
import io.namastack.outbox.completion.JdbcOutboxCompletionBatcher
import io.namastack.outbox.completion.JdbcOutboxCompletionBatchingPersistence
import io.namastack.outbox.completion.OutboxCompletionPersistence
import io.namastack.outbox.query.JdbcOutboxRecordQueriesFactory
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.transaction.support.TransactionTemplate
import javax.sql.DataSource

/**
 * Configures JDBC completion batching when enabled.
 *
 * @author Roland Beisel
 * @since 1.12.0
 */
@AutoConfiguration(
    after = [JdbcOutboxAutoConfiguration::class],
    before = [OutboxCoreProcessingAutoConfiguration::class],
)
@ConditionalOnSingleRuntimeMode
@ConditionalOnProperty(name = ["namastack.outbox.enabled"], havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "namastack.outbox.jdbc.completion-batching", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(JdbcOutboxConfigurationProperties::class)
class JdbcOutboxCompletionAutoConfiguration {
    @Bean("outboxCompletionExecutor", autowireCandidate = false)
    @ConditionalOnMissingBean(name = ["outboxCompletionExecutor"])
    internal fun outboxCompletionExecutor(properties: JdbcOutboxConfigurationProperties): ThreadPoolTaskExecutor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = properties.completionBatching.writerCount
            maxPoolSize = properties.completionBatching.writerCount
            queueCapacity = 0
            isDaemon = true
            setThreadNamePrefix("outbox-jdbc-completion-writer-")
            setWaitForTasksToCompleteOnShutdown(true)
        }

    @Bean
    @ConditionalOnMissingBean
    internal fun jdbcOutboxCompletionBatcher(
        beanFactory: BeanFactory,
        dataSource: DataSource,
        tableNameResolver: JdbcTableNameResolver,
        jdbcProperties: JdbcOutboxConfigurationProperties,
        outboxProperties: OutboxProperties,
    ): JdbcOutboxCompletionBatcher {
        val transactions = beanFactory.getBean<TransactionTemplate>("outboxTransactionTemplate")
        val completionDataSource =
            (transactions.transactionManager as? DataSourceTransactionManager)?.dataSource ?: dataSource

        return JdbcOutboxCompletionBatcher(
            writer =
                JdbcOutboxCompletionBatchWriter(
                    jdbc = NamedParameterJdbcTemplate(completionDataSource),
                    transactions = transactions,
                    queries = {
                        JdbcOutboxRecordQueriesFactory.create(
                            JdbcDatabaseType.detect(completionDataSource),
                            tableNameResolver.outboxRecord,
                        )
                    },
                ),
            executor = beanFactory.getBean<ThreadPoolTaskExecutor>("outboxCompletionExecutor"),
            settings = jdbcProperties.completionBatching,
            shutdownTimeout = outboxProperties.processing.effectiveShutdownTimeout,
        )
    }

    @Bean
    @ConditionalOnMissingBean(OutboxCompletionPersistence::class)
    internal fun batchingOutboxCompletionPersistence(
        recordRepository: OutboxRecordRepository,
        batcher: JdbcOutboxCompletionBatcher,
    ): OutboxCompletionPersistence = JdbcOutboxCompletionBatchingPersistence(recordRepository, batcher)
}
