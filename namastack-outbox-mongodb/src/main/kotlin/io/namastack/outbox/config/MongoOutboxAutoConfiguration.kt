package io.namastack.outbox.config

import io.namastack.outbox.MongoCollectionNameResolver
import io.namastack.outbox.MongoOutboxInstanceRepository
import io.namastack.outbox.MongoOutboxPartitionAssignmentRepository
import io.namastack.outbox.MongoOutboxRecordEntityMapper
import io.namastack.outbox.MongoOutboxRecordRepository
import io.namastack.outbox.OutboxPayloadSerializer
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.OutboxRecordStatusRepository
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
import org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.data.mongodb.MongoDatabaseFactory
import org.springframework.data.mongodb.MongoTransactionManager
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

/**
 * Auto-configuration for MongoDB-based outbox implementation.
 *
 * @author Stellar Hold
 * @since 1.5.0
 */
@AutoConfiguration
@AutoConfigureAfter(
    value = [
        TransactionAutoConfiguration::class,
        MongoAutoConfiguration::class,
    ],
)
@AutoConfigureBefore(OutboxCoreInfrastructureAutoConfiguration::class)
@ConditionalOnClass(MongoTemplate::class)
@ConditionalOnProperty(name = ["namastack.outbox.enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(MongoOutboxConfigurationProperties::class)
@EnableTransactionManagement
class MongoOutboxAutoConfiguration {
    /**
     * Provides a default Clock bean if none is configured.
     *
     * @return System default zone clock
     */
    @Bean
    @ConditionalOnMissingBean
    fun clock(): Clock = Clock.systemDefaultZone()

    /**
     * Creates the collection name resolver for MongoDB operations.
     *
     * @param properties Configuration properties for collection prefix
     * @return Collection name resolver for constructing collection names
     */
    @Bean
    @ConditionalOnMissingBean
    internal fun mongoCollectionNameResolver(
        properties: MongoOutboxConfigurationProperties,
    ): MongoCollectionNameResolver = MongoCollectionNameResolver(properties)

    /**
     * Creates a MongoTransactionManager for multi-document transaction support.
     * Required for atomic partition reassignment across multiple documents.
     *
     * @param databaseFactory the MongoDB database factory
     * @return transaction manager for MongoDB
     */
    @Bean("outboxMongoTransactionManager")
    @ConditionalOnMissingBean(name = ["outboxMongoTransactionManager"])
    internal fun outboxMongoTransactionManager(databaseFactory: MongoDatabaseFactory): MongoTransactionManager =
        MongoTransactionManager(databaseFactory)

    /**
     * Creates a TransactionTemplate for programmatic transaction management.
     *
     * @param beanFactory BeanFactory for retrieving the outbox-specific transaction manager
     * @return transaction template for outbox MongoDB operations
     */
    @Bean("outboxMongoTransactionTemplate")
    @ConditionalOnMissingBean(name = ["outboxMongoTransactionTemplate"])
    internal fun outboxMongoTransactionTemplate(beanFactory: BeanFactory): TransactionTemplate {
        val transactionManager = beanFactory.getBean<MongoTransactionManager>("outboxMongoTransactionManager")

        return TransactionTemplate(transactionManager)
    }

    /**
     * Creates the entity mapper for outbox records.
     *
     * @param payloadSerializer Serializer for payload and context
     * @return Mapper for converting between domain objects and MongoDB documents
     */
    @Bean
    @ConditionalOnMissingBean
    internal fun mongoOutboxRecordEntityMapper(
        payloadSerializer: OutboxPayloadSerializer,
    ): MongoOutboxRecordEntityMapper = MongoOutboxRecordEntityMapper(payloadSerializer)

    /**
     * Creates a MongoDB-based outbox record repository.
     *
     * @param mongoTemplate MongoTemplate for database operations
     * @param entityMapper Mapper for converting between domain objects and MongoDB documents
     * @param clock Clock for time-based operations
     * @return MongoDB outbox record repository implementation
     */
    @Bean(name = ["outboxRecordRepository", "outboxRecordStatusRepository"])
    @ConditionalOnMissingBean(OutboxRecordRepository::class, OutboxRecordStatusRepository::class)
    internal fun outboxRecordRepository(
        mongoTemplate: MongoTemplate,
        entityMapper: MongoOutboxRecordEntityMapper,
        clock: Clock,
        collectionNameResolver: MongoCollectionNameResolver,
    ): MongoOutboxRecordRepository =
        MongoOutboxRecordRepository(mongoTemplate, entityMapper, clock, collectionNameResolver)

    /**
     * Creates a MongoDB-based outbox instance repository.
     *
     * @param mongoTemplate MongoTemplate for database operations
     * @return MongoDB outbox instance repository implementation
     */
    @Bean
    @ConditionalOnMissingBean
    internal fun outboxInstanceRepository(
        mongoTemplate: MongoTemplate,
        collectionNameResolver: MongoCollectionNameResolver,
    ): OutboxInstanceRepository = MongoOutboxInstanceRepository(mongoTemplate, collectionNameResolver)

    /**
     * Creates a MongoDB-based outbox partition assignment repository.
     *
     * @param mongoTemplate MongoTemplate for database operations
     * @param beanFactory BeanFactory for retrieving the outbox-specific transaction template
     * @return MongoDB outbox partition assignment repository implementation
     */
    @Bean
    @ConditionalOnMissingBean
    internal fun partitionAssignmentRepository(
        mongoTemplate: MongoTemplate,
        beanFactory: BeanFactory,
        collectionNameResolver: MongoCollectionNameResolver,
    ): PartitionAssignmentRepository {
        val transactionTemplate = beanFactory.getBean<TransactionTemplate>("outboxMongoTransactionTemplate")

        return MongoOutboxPartitionAssignmentRepository(mongoTemplate, transactionTemplate, collectionNameResolver)
    }
}
