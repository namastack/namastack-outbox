package io.namastack.outbox

import io.namastack.outbox.partition.PartitionCoordinator
import io.namastack.outbox.retry.OutboxRetryPolicy
import io.namastack.outbox.retry.OutboxRetryPolicyFactory
import org.springframework.beans.factory.BeanFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurationPackage
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import java.time.Clock

/**
 * Auto-configuration class for Outbox core functionality.
 *
 * This configuration is activated when the @EnableOutbox annotation is present
 * and provides all necessary beans for the outbox pattern implementation.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@AutoConfiguration
@AutoConfigurationPackage
@ConditionalOnBean(annotation = [EnableOutbox::class])
@EnableConfigurationProperties(OutboxProperties::class)
class OutboxCoreAutoConfiguration {
    /**
     * Provides a default Clock bean if none is configured.
     *
     * @return System default zone clock
     */
    @Bean
    @ConditionalOnMissingBean
    fun clock(): Clock = Clock.systemDefaultZone()

    /**
     * Creates a retry policy based on configuration properties.
     *
     * @param properties Outbox configuration properties
     * @return Configured retry policy
     */
    @Bean
    @ConditionalOnMissingBean
    fun retryPolicy(properties: OutboxProperties): OutboxRetryPolicy =
        OutboxRetryPolicyFactory.create(name = properties.retry.policy, retryProperties = properties.retry)

    /**
     * Creates the outbox instance registry for horizontal scaling.
     *
     * @param instanceRepository Repository for instance persistence
     * @param properties Configuration properties for outbox functionality
     * @param clock Clock for time operations
     * @return OutboxInstanceRegistry bean
     */
    @Bean
    @ConditionalOnMissingBean
    fun outboxInstanceRegistry(
        instanceRepository: OutboxInstanceRepository,
        properties: OutboxProperties,
        clock: Clock,
    ): OutboxInstanceRegistry = OutboxInstanceRegistry(instanceRepository, properties, clock)

    /**
     * Creates the partition-aware outbox processing scheduler.
     *
     * @param recordRepository Repository for accessing outbox records
     * @param recordProcessor Processor for handling individual records
     * @param partitionCoordinator Coordinator for partition assignments
     * @param instanceRegistry Registry for instance management
     * @param retryPolicy Policy for determining retry behavior
     * @param properties Configuration properties
     * @param clock Clock for time-based operations
     * @return PartitionAwareOutboxProcessingScheduler bean
     */
    @Bean
    @ConditionalOnMissingBean
    fun partitionAwareOutboxProcessingScheduler(
        recordRepository: OutboxRecordRepository,
        recordProcessor: OutboxRecordProcessor,
        partitionCoordinator: PartitionCoordinator,
        instanceRegistry: OutboxInstanceRegistry,
        retryPolicy: OutboxRetryPolicy,
        properties: OutboxProperties,
        clock: Clock,
    ): OutboxProcessingScheduler =
        OutboxProcessingScheduler(
            recordRepository,
            recordProcessor,
            partitionCoordinator,
            instanceRegistry,
            retryPolicy,
            properties,
            clock,
        )

    /**
     * Creates the partition coordinator for managing partition assignments.
     *
     * @param instanceRegistry Registry for managing instances
     * @return PartitionCoordinator bean
     */
    @Bean
    @ConditionalOnMissingBean
    fun partitionCoordinator(instanceRegistry: OutboxInstanceRegistry): PartitionCoordinator =
        PartitionCoordinator(instanceRegistry)

    /**
     * Creates the custom application event multicaster for @OutboxEvent handling.
     *
     * Intercepts events annotated with @OutboxEvent, persists them to the outbox,
     * and optionally publishes them to listeners (controlled by publishAfterSave config).
     *
     * Only created if [OutboxEventSerializer] is available (Jackson module loaded).
     *
     * @param beanFactory Factory for creating nested beans
     * @param outboxRecordRepository Repository for persisting outbox records
     * @param outboxEventSerializer Serializer for event payloads
     * @param outboxProperties Configuration properties
     * @param clock Clock for timestamps
     * @return OutboxEventMulticaster bean
     */
    @Bean(name = ["applicationEventMulticaster"])
    @ConditionalOnMissingBean
    fun outboxApplicationEventMulticaster(
        beanFactory: BeanFactory,
        outboxRecordRepository: OutboxRecordRepository,
        outboxEventSerializer: OutboxEventSerializer,
        outboxProperties: OutboxProperties,
        clock: Clock,
    ): OutboxEventMulticaster =
        OutboxEventMulticaster(beanFactory, outboxRecordRepository, outboxEventSerializer, outboxProperties, clock)
}
