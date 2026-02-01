package io.namastack.outbox

import io.namastack.outbox.instance.OutboxInstanceRepository
import io.namastack.outbox.partition.PartitionAssignmentRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigureAfter
import org.springframework.boot.autoconfigure.AutoConfigureBefore
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

/**
 * Auto-configuration class for JPA-based Outbox functionality.
 *
 * This configuration provides JPA implementations for outbox repositories.
 * Requires both JPA (EntityManagerFactory) and the outbox core module to be present.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@AutoConfiguration
@AutoConfigureAfter(TransactionAutoConfiguration::class)
@AutoConfigureBefore(OutboxCoreAutoConfiguration::class)
@ConditionalOnClass(EntityManagerFactory::class, OutboxService::class)
@ConditionalOnProperty(name = ["namastack.outbox.enabled"], havingValue = "true", matchIfMissing = true)
class JpaOutboxAutoConfiguration {
    /**
     * Provides a default Clock bean if none is configured.
     *
     * @return System default zone clock
     */
    @Bean
    @ConditionalOnMissingBean
    fun clock(): Clock = Clock.systemDefaultZone()

    /**
     * Creates a named EntityManager for outbox operations.
     *
     * This creates a shared, transaction-aware EntityManager proxy that automatically
     * participates in Spring-managed transactions. Users can override this by providing
     * their own "outboxEntityManager" bean.
     *
     * @param entityManagerFactory Primary JPA entity manager factory
     * @return Shared, transaction-aware entity manager for outbox operations
     */
    @Bean("outboxEntityManager", autowireCandidate = false)
    @ConditionalOnMissingBean(name = ["outboxEntityManager"])
    internal fun outboxEntityManager(entityManagerFactory: EntityManagerFactory): EntityManager =
        SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory)

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
     * Creates a JPA-based outbox record repository.
     *
     * @param beanFactory Bean factory for retrieving outbox-specific beans
     * @param outboxRecordEntityMapper Mapper for converting between domain objects and JPA entities
     * @param clock Clock for time-based operations
     * @return JPA outbox record repository implementation
     */
    @Bean
    @ConditionalOnMissingBean
    internal fun outboxRecordRepository(
        beanFactory: BeanFactory,
        outboxRecordEntityMapper: OutboxRecordEntityMapper,
        clock: Clock,
    ): OutboxRecordRepository =
        JpaOutboxRecordRepository(
            entityManager = beanFactory.getBean<EntityManager>("outboxEntityManager"),
            transactionTemplate = beanFactory.getBean<TransactionTemplate>("outboxTransactionTemplate"),
            entityMapper = outboxRecordEntityMapper,
            clock = clock,
        )

    /**
     * Creates a JPA-based outbox instance repository.
     *
     * @param beanFactory Bean factory for retrieving outbox-specific beans
     * @return JPA outbox instance repository implementation
     */
    @Bean
    @ConditionalOnMissingBean
    internal fun outboxInstanceRepository(beanFactory: BeanFactory): OutboxInstanceRepository =
        JpaOutboxInstanceRepository(
            entityManager = beanFactory.getBean<EntityManager>("outboxEntityManager"),
            transactionTemplate = beanFactory.getBean<TransactionTemplate>("outboxTransactionTemplate"),
        )

    /**
     * Creates a JPA-based outbox partition repository.
     *
     * @param beanFactory Bean factory for retrieving outbox-specific beans
     * @return JPA outbox partition repository implementation
     */
    @Bean
    @ConditionalOnMissingBean
    internal fun outboxPartitionAssignmentRepository(beanFactory: BeanFactory): PartitionAssignmentRepository =
        JpaOutboxPartitionAssignmentRepository(
            entityManager = beanFactory.getBean<EntityManager>("outboxEntityManager"),
            transactionTemplate = beanFactory.getBean<TransactionTemplate>("outboxTransactionTemplate"),
        )

    /**
     * Creates the entity mapper for outbox records.
     *
     * @param recordSerializer Serializer for payload and context
     * @return Mapper for converting between domain objects and JPA entities
     */
    @Bean
    @ConditionalOnMissingBean
    internal fun outboxRecordEntityMapper(recordSerializer: OutboxPayloadSerializer): OutboxRecordEntityMapper =
        OutboxRecordEntityMapper(recordSerializer)
}
