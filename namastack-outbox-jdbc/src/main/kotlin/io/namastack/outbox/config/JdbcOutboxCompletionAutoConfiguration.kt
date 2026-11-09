package io.namastack.outbox.config

import io.namastack.outbox.JdbcOutboxCompletionBatchWriter
import io.namastack.outbox.JdbcTableNameResolver
import io.namastack.outbox.completion.OutboxCompletionWriter
import io.namastack.outbox.query.JdbcOutboxRecordQueriesFactory
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
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
    before = [OutboxCompletionAutoConfiguration::class],
)
@ConditionalOnSingleRuntimeMode
@ConditionalOnProperty(name = ["namastack.outbox.enabled"], havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "namastack.outbox.completion-batching", name = ["enabled"], havingValue = "true")
class JdbcOutboxCompletionAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(OutboxCompletionWriter::class)
    internal fun jdbcOutboxCompletionWriter(
        beanFactory: BeanFactory,
        dataSource: DataSource,
        tableNameResolver: JdbcTableNameResolver,
    ): OutboxCompletionWriter {
        val transactions = beanFactory.getBean<TransactionTemplate>("outboxTransactionTemplate")
        val completionDataSource =
            (transactions.transactionManager as? DataSourceTransactionManager)?.dataSource ?: dataSource

        return JdbcOutboxCompletionBatchWriter(
            jdbc = NamedParameterJdbcTemplate(completionDataSource),
            transactions = transactions,
            queries = {
                JdbcOutboxRecordQueriesFactory.create(
                    databaseType = JdbcDatabaseType.detect(completionDataSource),
                    tableName = tableNameResolver.outboxRecord,
                )
            },
        )
    }
}
