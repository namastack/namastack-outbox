package io.namastack.outbox.config

import io.namastack.outbox.*
import io.namastack.outbox.instance.OutboxInstanceRepository
import io.namastack.outbox.partition.PartitionAssignmentRepository
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigureBefore
import io.namastack.outbox.config.OutboxCoreInfrastructureAutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.data.mongodb.MongoDatabaseFactory
import org.springframework.data.mongodb.MongoTransactionManager
import org.springframework.data.mongodb.core.MongoTemplate
import java.time.Clock
import org.springframework.transaction.support.TransactionTemplate

/**
 * Auto-configuration for MongoDB-based outbox implementation.
 *
 * @author Stellar Hold
 * @since 1.1.0
 */
@AutoConfiguration
@AutoConfigureBefore(OutboxCoreInfrastructureAutoConfiguration::class)
@ConditionalOnClass(MongoTemplate::class)
@ConditionalOnProperty(name = ["namastack.outbox.enabled"], havingValue = "true", matchIfMissing = true)
class MongoOutboxAutoConfiguration {

    /**
     * Creates a MongoTransactionManager for multi-document transaction support.
     * Required for atomic partition reassignment across multiple documents.
     *
     * @param dbFactory the MongoDB database factory
     * @return transaction manager for MongoDB
     */
    @Bean("outboxMongoTransactionManager")
    @ConditionalOnMissingBean(name = ["outboxMongoTransactionManager"])
    internal fun outboxMongoTransactionManager(dbFactory: MongoDatabaseFactory): MongoTransactionManager =
        MongoTransactionManager(dbFactory)

    /**
     * Creates a TransactionTemplate for programmatic transaction management.
     *
     * @param beanFactory BeanFactory for retrieving the outbox-specific transaction manager
     * @return transaction template for outbox MongoDB operations
     */
    @Bean("outboxMongoTransactionTemplate")
    @ConditionalOnMissingBean(name = ["outboxMongoTransactionTemplate"])
    internal fun outboxMongoTransactionTemplate(beanFactory: BeanFactory): TransactionTemplate =
        TransactionTemplate(beanFactory.getBean<MongoTransactionManager>("outboxMongoTransactionManager"))

    /**
     * Provides a default Clock bean if none is configured.
     *
     * @return System default zone clock
     */
    @Bean
    @ConditionalOnMissingBean
    fun clock(): Clock = Clock.systemDefaultZone()

    @Bean
    @ConditionalOnMissingBean
    internal fun mongoOutboxRecordEntityMapper(serializer: OutboxPayloadSerializer): MongoOutboxRecordEntityMapper =
        MongoOutboxRecordEntityMapper(serializer)

    @Bean(name = ["outboxRecordRepository", "outboxRecordStatusRepository"])
    @ConditionalOnMissingBean(OutboxRecordRepository::class, OutboxRecordStatusRepository::class)
    internal fun outboxRecordRepository(
        mongoTemplate: MongoTemplate,
        entityMapper: MongoOutboxRecordEntityMapper,
        clock: Clock,
    ): MongoOutboxRecordRepository = MongoOutboxRecordRepository(mongoTemplate, entityMapper, clock)

    @Bean
    @ConditionalOnMissingBean
    internal fun outboxInstanceRepository(
        mongoTemplate: MongoTemplate,
    ): OutboxInstanceRepository =
        MongoOutboxInstanceRepository(mongoTemplate)

    @Bean
    @ConditionalOnMissingBean
    internal fun partitionAssignmentRepository(
        mongoTemplate: MongoTemplate,
        beanFactory: BeanFactory,
    ): PartitionAssignmentRepository =
        MongoOutboxPartitionAssignmentRepository(
            mongoTemplate,
            beanFactory.getBean<TransactionTemplate>("outboxMongoTransactionTemplate"),
        )
}
