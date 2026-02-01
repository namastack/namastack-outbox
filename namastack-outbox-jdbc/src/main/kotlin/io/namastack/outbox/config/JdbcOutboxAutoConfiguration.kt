package io.namastack.outbox.config

import io.namastack.outbox.JdbcOutboxInstanceRepository
import io.namastack.outbox.JdbcOutboxPartitionAssignmentRepository
import io.namastack.outbox.JdbcOutboxRecordEntityMapper
import io.namastack.outbox.JdbcOutboxRecordRepository
import io.namastack.outbox.JdbcTableNameResolver
import io.namastack.outbox.OutboxCoreAutoConfiguration
import io.namastack.outbox.OutboxPayloadSerializer
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.OutboxService
import io.namastack.outbox.instance.OutboxInstanceRepository
import io.namastack.outbox.partition.PartitionAssignmentRepository
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigureAfter
import org.springframework.boot.autoconfigure.AutoConfigureBefore
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import javax.sql.DataSource

/**
 * Auto-configuration class for JDBC-based Outbox functionality.
 *
 * This configuration provides JDBC implementations for outbox repositories.
 * Requires both JDBC (JdbcClient) and the outbox core module to be present.
 *
 * @author Roland Beisel
 * @since 1.0.0
 */
@AutoConfiguration
@AutoConfigureAfter(
    value = [
        TransactionAutoConfiguration::class,
        JdbcClientAutoConfiguration::class,
    ],
)
@AutoConfigureBefore(OutboxCoreAutoConfiguration::class)
@ConditionalOnClass(JdbcClient::class, OutboxService::class)
@ConditionalOnProperty(name = ["namastack.outbox.enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(JdbcOutboxConfigurationProperties::class)
class JdbcOutboxAutoConfiguration {
    /**
     * Provides a default Clock bean if none is configured.
     *
     * @return System default zone clock
     */
    @Bean
    @ConditionalOnMissingBean
    fun clock(): Clock = Clock.systemDefaultZone()

    /**
     * Creates the table name resolver for JDBC operations.
     *
     * @param properties Configuration properties for table prefix and schema
     * @return Table name resolver for constructing fully qualified table names
     */
    @Bean
    @ConditionalOnMissingBean
    internal fun jdbcTableNameResolver(properties: JdbcOutboxConfigurationProperties): JdbcTableNameResolver =
        JdbcTableNameResolver(properties)

    /**
     * Creates a named JdbcClient for outbox operations.
     *
     * Users can override this by providing their own "outboxJdbcClient" bean.
     *
     * @param dataSource DataSource to use
     * @return JdbcClient for outbox operations
     */
    @Bean("outboxJdbcClient", autowireCandidate = false)
    @ConditionalOnMissingBean(name = ["outboxJdbcClient"])
    internal fun outboxJdbcClient(dataSource: DataSource): JdbcClient = JdbcClient.create(dataSource)

    /**
     * Creates a transaction template for outbox operations.
     *
     * @param transactionManager Platform transaction manager
     * @return Transaction template for outbox operations
     */
    @Bean("outboxTransactionTemplate", autowireCandidate = false)
    @ConditionalOnMissingBean(name = ["outboxTransactionTemplate"])
    internal fun outboxTransactionTemplate(transactionManager: PlatformTransactionManager): TransactionTemplate =
        TransactionTemplate(transactionManager)

    /**
     * Creates a JDBC-based outbox record repository.
     *
     * @param beanFactory Bean factory for retrieving outbox-specific beans
     * @param jdbcOutboxRecordEntityMapper Mapper for converting between domain objects and entities
     * @param clock Clock for time-based operations
     * @param tableNameResolver Resolver for fully qualified table names
     * @return JDBC outbox record repository implementation
     */
    @Bean
    @ConditionalOnMissingBean
    internal fun outboxRecordRepository(
        beanFactory: BeanFactory,
        jdbcOutboxRecordEntityMapper: JdbcOutboxRecordEntityMapper,
        clock: Clock,
        tableNameResolver: JdbcTableNameResolver,
    ): OutboxRecordRepository =
        JdbcOutboxRecordRepository(
            jdbcClient = beanFactory.getBean<JdbcClient>("outboxJdbcClient"),
            transactionTemplate = beanFactory.getBean<TransactionTemplate>("outboxTransactionTemplate"),
            entityMapper = jdbcOutboxRecordEntityMapper,
            clock = clock,
            tableNameResolver = tableNameResolver,
        )

    /**
     * Creates the entity mapper for outbox records.
     *
     * @param recordSerializer Serializer for payload and context
     * @return Mapper for converting between domain objects and entities
     */
    @Bean
    @ConditionalOnMissingBean
    internal fun outboxRecordEntityMapper(recordSerializer: OutboxPayloadSerializer): JdbcOutboxRecordEntityMapper =
        JdbcOutboxRecordEntityMapper(recordSerializer)

    /**
     * Creates a JDBC-based outbox instance repository.
     *
     * @param beanFactory Bean factory for retrieving outbox-specific beans
     * @param tableNameResolver Resolver for fully qualified table names
     * @return JDBC outbox instance repository implementation
     */
    @Bean
    @ConditionalOnMissingBean
    internal fun outboxInstanceRepository(
        beanFactory: BeanFactory,
        tableNameResolver: JdbcTableNameResolver,
    ): OutboxInstanceRepository =
        JdbcOutboxInstanceRepository(
            jdbcClient = beanFactory.getBean<JdbcClient>("outboxJdbcClient"),
            transactionTemplate = beanFactory.getBean<TransactionTemplate>("outboxTransactionTemplate"),
            tableNameResolver = tableNameResolver,
        )

    /**
     * Creates a JDBC-based outbox partition repository.
     *
     * @param beanFactory Bean factory for retrieving outbox-specific beans
     * @param tableNameResolver Resolver for fully qualified table names
     * @return JDBC outbox partition repository implementation
     */
    @Bean
    @ConditionalOnMissingBean
    internal fun outboxPartitionAssignmentRepository(
        beanFactory: BeanFactory,
        tableNameResolver: JdbcTableNameResolver,
    ): PartitionAssignmentRepository =
        JdbcOutboxPartitionAssignmentRepository(
            jdbcClient = beanFactory.getBean<JdbcClient>("outboxJdbcClient"),
            transactionTemplate = beanFactory.getBean<TransactionTemplate>("outboxTransactionTemplate"),
            tableNameResolver = tableNameResolver,
        )
}
