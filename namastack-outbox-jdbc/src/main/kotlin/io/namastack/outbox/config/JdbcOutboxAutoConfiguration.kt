package io.namastack.outbox.config

import io.namastack.outbox.JdbcOutboxInstanceRepository
import io.namastack.outbox.JdbcOutboxPartitionAssignmentRepository
import io.namastack.outbox.JdbcOutboxRecordEntityMapper
import io.namastack.outbox.JdbcOutboxRecordRepository
import io.namastack.outbox.OutboxCoreAutoConfiguration
import io.namastack.outbox.OutboxPayloadSerializer
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.annotation.EnableOutbox
import io.namastack.outbox.instance.OutboxInstanceRepository
import io.namastack.outbox.partition.PartitionAssignmentRepository
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigureAfter
import org.springframework.boot.autoconfigure.AutoConfigureBefore
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
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
 *
 * @author Roland Beisel
 * @since 1.1.0
 */
@AutoConfiguration
@AutoConfigureAfter(
    value = [
        TransactionAutoConfiguration::class,
        JdbcClientAutoConfiguration::class,
    ],
)
@AutoConfigureBefore(OutboxCoreAutoConfiguration::class)
@ConditionalOnBean(annotation = [EnableOutbox::class])
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
     * @param jdbcOutboxRecordEntityMapper Mapper for converting between domain objects and JPA entities
     * @param clock Clock for time-based operations
     * @return JDBC outbox record repository implementation
     */
    @Bean
    @ConditionalOnMissingBean
    internal fun outboxRecordRepository(
        beanFactory: BeanFactory,
        jdbcOutboxRecordEntityMapper: JdbcOutboxRecordEntityMapper,
        clock: Clock,
    ): OutboxRecordRepository =
        JdbcOutboxRecordRepository(
            jdbcClient = beanFactory.getBean<JdbcClient>("outboxJdbcClient"),
            transactionTemplate = beanFactory.getBean<TransactionTemplate>("outboxTransactionTemplate"),
            entityMapper = jdbcOutboxRecordEntityMapper,
            clock = clock,
        )

    /**
     * Creates the entity mapper for outbox records.
     *
     * @param recordSerializer Serializer for payload and context
     * @return Mapper for converting between domain objects and JPA entities
     */
    @Bean
    @ConditionalOnMissingBean
    internal fun outboxRecordEntityMapper(recordSerializer: OutboxPayloadSerializer): JdbcOutboxRecordEntityMapper =
        JdbcOutboxRecordEntityMapper(recordSerializer)

    /**
     * Creates a JDBC-based outbox instance repository.
     *
     * @param beanFactory Bean factory for retrieving outbox-specific beans
     * @return JDBC outbox instance repository implementation
     */
    @Bean
    @ConditionalOnMissingBean
    internal fun outboxInstanceRepository(beanFactory: BeanFactory): OutboxInstanceRepository =
        JdbcOutboxInstanceRepository(
            jdbcClient = beanFactory.getBean<JdbcClient>("outboxJdbcClient"),
            transactionTemplate = beanFactory.getBean<TransactionTemplate>("outboxTransactionTemplate"),
        )

    /**
     * Creates a JDBC-based outbox partition repository.
     *
     * @param beanFactory Bean factory for retrieving outbox-specific beans
     * @return JDBC outbox partition repository implementation
     */
    @Bean
    @ConditionalOnMissingBean
    internal fun outboxPartitionAssignmentRepository(beanFactory: BeanFactory): PartitionAssignmentRepository =
        JdbcOutboxPartitionAssignmentRepository(
            jdbcClient = beanFactory.getBean<JdbcClient>("outboxJdbcClient"),
            transactionTemplate = beanFactory.getBean<TransactionTemplate>("outboxTransactionTemplate"),
        )
}
