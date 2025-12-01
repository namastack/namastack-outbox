package io.namastack.outbox

import io.namastack.outbox.instance.OutboxInstanceRegistry
import io.namastack.outbox.instance.OutboxInstanceRepository
import io.namastack.outbox.partition.PartitionAssignmentRepository
import io.namastack.outbox.partition.PartitionCoordinator
import io.namastack.outbox.retry.OutboxRetryPolicy
import io.namastack.outbox.retry.OutboxRetryPolicyFactory
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurationPackage
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.event.SimpleApplicationEventMulticaster
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
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
     * Provides a ThreadPoolTaskExecutor for parallel processing of aggregateIds.
     *
     * The pool size is configurable via OutboxProperties. Used by OutboxProcessingScheduler
     * to process multiple aggregateIds in parallel while maintaining strict ordering per aggregateId.
     *
     * @param properties Outbox configuration properties
     * @return Configured ThreadPoolTaskExecutor
     */
    @Bean
    @ConditionalOnMissingBean(name = ["outboxTaskExecutor"])
    fun outboxTaskExecutor(properties: OutboxProperties): ThreadPoolTaskExecutor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = properties.processing.executorCorePoolSize
        executor.maxPoolSize = properties.processing.executorMaxPoolSize
        executor.setThreadNamePrefix("outbox-proc-")
        executor.setWaitForTasksToCompleteOnShutdown(true)
        executor.initialize()

        return executor
    }

    /**
     * Scheduler for general outbox tasks (e.g. batch processing).
     *
     * Pool size is set to 5 by default. Only used internally by the outbox library.
     *
     * @return ThreadPoolTaskScheduler for outbox jobs
     */
    @Bean
    @ConditionalOnMissingBean(name = ["outboxDefaultScheduler"])
    fun outboxDefaultScheduler(): ThreadPoolTaskScheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = 5
            setThreadNamePrefix("outbox-scheduler-")
            setWaitForTasksToCompleteOnShutdown(true)
            initialize()
        }

    /**
     * Scheduler for heartbeat and rebalance tasks.
     *
     * Pool size is set to 1. Used for periodic signals and partition rebalancing.
     *
     * @return ThreadPoolTaskScheduler for heartbeat/rebalance jobs
     */
    @Bean
    @ConditionalOnMissingBean(name = ["outboxRebalancingScheduler"])
    fun outboxRebalancingScheduler(): ThreadPoolTaskScheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = 1
            setThreadNamePrefix("outbox-rebalancing-")
            setWaitForTasksToCompleteOnShutdown(true)
            initialize()
        }

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
     * Creates the partition coordinator for managing partition assignments.
     *
     * @param instanceRegistry Registry for active instances and current instance identification
     * @param partitionAssignmentRepository Repository for persisting partition assignments
     * @param clock Clock for timestamp generation
     * @return PartitionCoordinator bean for managing partition lifecycle
     */
    @Bean
    @ConditionalOnMissingBean
    fun partitionCoordinator(
        instanceRegistry: OutboxInstanceRegistry,
        partitionAssignmentRepository: PartitionAssignmentRepository,
        clock: Clock,
    ): PartitionCoordinator =
        PartitionCoordinator(
            instanceRegistry = instanceRegistry,
            partitionAssignmentRepository = partitionAssignmentRepository,
            clock = clock,
        )

    /**
     * Creates the partition-aware outbox processing scheduler.
     *
     * @param recordRepository Repository for accessing outbox records
     * @param recordProcessor Processor for handling individual records
     * @param partitionCoordinator Coordinator for partition assignments
     * @param taskExecutor TaskExecutor for parallel processing of aggregateIds
     * @param retryPolicy Policy for determining retry behavior
     * @param properties Configuration properties
     * @param clock Clock for time-based operations
     * @return PartitionAwareOutboxProcessingScheduler bean
     */
    @Bean
    @ConditionalOnMissingBean
    fun partitionAwareOutboxProcessingScheduler(
        recordRepository: OutboxRecordRepository,
        @Qualifier("delegatingOutboxRecordProcessor") recordProcessor: OutboxRecordProcessor,
        partitionCoordinator: PartitionCoordinator,
        retryPolicy: OutboxRetryPolicy,
        properties: OutboxProperties,
        @Qualifier("outboxTaskExecutor") taskExecutor: TaskExecutor,
        clock: Clock,
    ): OutboxProcessingScheduler =
        OutboxProcessingScheduler(
            recordRepository = recordRepository,
            recordProcessor = recordProcessor,
            partitionCoordinator = partitionCoordinator,
            retryPolicy = retryPolicy,
            properties = properties,
            taskExecutor = taskExecutor,
            clock = clock,
        )

    @Bean(name = ["delegatingOutboxRecordProcessor"])
    fun outboxRecordProcessor(processorRegistry: OutboxRecordProcessorRegistry): OutboxRecordProcessor =
        DelegatingOutboxRecordProcessor(processorRegistry)

    /**
     * Creates the OutboxRecordProcessorRegistry bean and registers all OutboxRecordProcessor implementations.
     *
     * This registry collects all beans of type OutboxRecordProcessor from the Spring context using their bean names as keys.
     * It enables dynamic lookup and delegation to multiple processors, supporting flexible event processing and migration scenarios.
     * All processors available in the context at initialization time are included.
     *
     * @param applicationContext Spring application context used to discover processor beans
     * @return OutboxRecordProcessorRegistry containing all registered processors
     */
    @Bean
    @ConditionalOnMissingBean
    fun outboxRecordProcessorRegistry(applicationContext: ApplicationContext): OutboxRecordProcessorRegistry {
        val processors: Map<String, OutboxRecordProcessor> =
            applicationContext.getBeansOfType(OutboxRecordProcessor::class.java)

        return OutboxRecordProcessorRegistry(processors)
    }

    /**
     * Creates the Outbox bean for scheduling outbox records to all registered processors.
     *
     * This bean provides the main entry point for scheduling events in the outbox pattern.
     * It uses the OutboxRecordProcessorRegistry to discover all available processors and
     * creates a separate OutboxRecord for each processor, setting the processorBeanName accordingly.
     * Each record is then persisted via the OutboxRecordRepository.
     *
     * @param recordRepository Repository for persisting outbox records
     * @param recordProcessorRegistry Registry for discovering all registered processors
     * @return Outbox service instance
     */
    @Bean
    @ConditionalOnMissingBean
    fun outbox(
        recordRepository: OutboxRecordRepository,
        recordProcessorRegistry: OutboxRecordProcessorRegistry,
        clock: Clock,
    ): OutboxService = OutboxService(recordRepository, recordProcessorRegistry, clock)

    /**
     * Creates the custom application event multicaster for @OutboxEvent handling.
     *
     * This multicaster is only registered if 'outbox.multicaster.enabled' is true (default).
     * If enabled, the OutboxEventMulticaster bean is provided and events annotated with @OutboxEvent
     * are published via the outbox mechanism. If disabled, the default Spring multicaster is used.
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
    @ConditionalOnProperty(name = ["outbox.multicaster.enabled"], havingValue = "true", matchIfMissing = true)
    fun outboxApplicationEventMulticaster(
        beanFactory: BeanFactory,
        outboxRecordRepository: OutboxRecordRepository,
        outboxRecordProcessorRegistry: OutboxRecordProcessorRegistry,
        outboxEventSerializer: OutboxEventSerializer,
        outboxProperties: OutboxProperties,
        clock: Clock,
    ): OutboxEventMulticaster =
        OutboxEventMulticaster(
            delegateEventMulticaster = SimpleApplicationEventMulticaster(beanFactory),
            outboxRecordRepository = outboxRecordRepository,
            outboxRecordProcessorRegistry = outboxRecordProcessorRegistry,
            outboxEventSerializer = outboxEventSerializer,
            outboxProperties = outboxProperties,
            clock = clock,
        )
}
