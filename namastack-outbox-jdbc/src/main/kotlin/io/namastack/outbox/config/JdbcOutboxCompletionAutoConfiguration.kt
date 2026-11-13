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
import org.springframework.transaction.support.TransactionTemplate

/**
 * Configures JDBC completion batching when enabled.
 *
 * The default writer shares outboxJdbcTemplate, outboxTransactionTemplate, and the table name resolver
 * with the JDBC repositories. A custom [OutboxCompletionWriter] bean replaces the default writer;
 * its implementation is responsible for using compatible persistence resources.
 *
 * @author Roland Beisel
 * @since 2.0.0
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
        tableNameResolver: JdbcTableNameResolver,
    ): OutboxCompletionWriter {
        val jdbc = beanFactory.getBean<NamedParameterJdbcTemplate>("outboxJdbcTemplate")

        return JdbcOutboxCompletionBatchWriter(
            jdbc = jdbc,
            transactions = beanFactory.getBean<TransactionTemplate>("outboxTransactionTemplate"),
            queries = {
                JdbcOutboxRecordQueriesFactory.create(
                    databaseType =
                        JdbcDatabaseType.detect(
                            checkNotNull(jdbc.jdbcTemplate.dataSource) { "outboxJdbcTemplate must have a DataSource" },
                        ),
                    tableName = tableNameResolver.outboxRecord,
                )
            },
        )
    }
}
