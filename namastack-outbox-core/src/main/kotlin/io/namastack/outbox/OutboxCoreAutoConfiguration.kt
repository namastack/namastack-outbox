package io.namastack.outbox

import io.namastack.outbox.annotation.EnableOutbox
import io.namastack.outbox.context.OutboxContextCollector
import io.namastack.outbox.context.OutboxContextProvider
import io.namastack.outbox.handler.OutboxHandlerBeanPostProcessor
import io.namastack.outbox.handler.invoker.OutboxFallbackHandlerInvoker
import io.namastack.outbox.handler.invoker.OutboxHandlerInvoker
import io.namastack.outbox.handler.registry.OutboxFallbackHandlerRegistry
import io.namastack.outbox.handler.registry.OutboxHandlerRegistry
import io.namastack.outbox.instance.OutboxInstanceRegistry
import io.namastack.outbox.instance.OutboxInstanceRepository
import io.namastack.outbox.partition.PartitionAssignmentRepository
import io.namastack.outbox.partition.PartitionCoordinator
import io.namastack.outbox.processor.FallbackOutboxRecordProcessor
import io.namastack.outbox.processor.OutboxRecordProcessor
import io.namastack.outbox.processor.PermanentFailureOutboxRecordProcessor
import io.namastack.outbox.processor.PrimaryOutboxRecordProcessor
import io.namastack.outbox.processor.RetryOutboxRecordProcessor
import io.namastack.outbox.retry.OutboxRetryPolicy
import io.namastack.outbox.retry.OutboxRetryPolicyFactory
import io.namastack.outbox.retry.OutboxRetryPolicyRegistry
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurationPackage
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Role
import org.springframework.context.event.SimpleApplicationEventMulticaster
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.time.Clock

/**
 * Auto-configuration class for Outbox core functionality.
 *
 * Activated when @EnableOutbox annotation is present. Configures handler discovery
 * and registration, partition-aware processing scheduling, retry policies, instance
 * coordination for distributed deployments, and optional event multicasting.
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
     * Used for all timestamp operations in the outbox system.
     * Can be overridden by providing a custom @Bean Clock in your configuration.
     *
     * @return System default zone clock
     */
    @Bean
    @ConditionalOnMissingBean
    fun clock(): Clock = Clock.systemDefaultZone()

    /**
     * Creates a ThreadPoolTaskExecutor for parallel record processing.
     *
     * Processes multiple record keys in parallel while maintaining strict ordering
     * within each record key. Pool size is configurable via:
     * - outbox.processing.executor-core-pool-size
     * - outbox.processing.executor-max-pool-size
     *
     * @param properties Outbox configuration properties
     * @return Configured ThreadPoolTaskExecutor
     */
    @Bean("outboxTaskExecutor")
    @ConditionalOnMissingBean(name = ["outboxTaskExecutor"])
    fun outboxTaskExecutor(
        builder: ThreadPoolTaskExecutorBuilder,
        properties: OutboxProperties,
    ): ThreadPoolTaskExecutor =
        builder
            .corePoolSize(properties.processing.executorCorePoolSize)
            .maxPoolSize(properties.processing.executorMaxPoolSize)
            .threadNamePrefix("outbox-proc-")
            .build()

    /**
     * Scheduler for general outbox batch processing tasks.
     *
     * Pool size: 5 (internal use only)
     * Used for periodic batch operations and scheduled maintenance.
     *
     * @return ThreadPoolTaskScheduler for outbox batch jobs
     */
    @Bean("outboxDefaultScheduler")
    @ConditionalOnMissingBean(name = ["outboxDefaultScheduler"])
    fun outboxDefaultScheduler(builder: ThreadPoolTaskSchedulerBuilder): ThreadPoolTaskScheduler =
        builder
            .poolSize(1)
            .threadNamePrefix("outbox-scheduler-")
            .build()

    /**
     * Scheduler for partition rebalancing and heartbeat signals.
     *
     * Pool size: 1 (single-threaded for coordination)
     * Used for distributed instance coordination and partition assignment.
     *
     * @return ThreadPoolTaskScheduler for coordination tasks
     */
    @Bean("outboxRebalancingScheduler")
    @ConditionalOnMissingBean(name = ["outboxRebalancingScheduler"])
    fun outboxRebalancingScheduler(builder: ThreadPoolTaskSchedulerBuilder): ThreadPoolTaskScheduler =
        builder
            .poolSize(1)
            .threadNamePrefix("outbox-rebalancing-")
            .build()

    /** Creates the default retry policy for failed record processing.
     *
     * This is the fallback policy used when handlers don't specify their own.
     * Policy type is configurable via outbox.retry.policy:
     * - exponential: Exponential backoff with jitter
     * - fixed: Fixed delay between retries
     * - linear: Linear backoff
     *
     * Handlers can override this by using @OutboxRetryable annotation or
     * implementing OutboxRetryAware interface.
     *
     * @param properties Outbox configuration including retry settings
     * @return Default configured OutboxRetryPolicy
     */
    @Bean("outboxRetryPolicy")
    @ConditionalOnMissingBean(name = ["outboxRetryPolicy"])
    fun defaultOutboxRetryPolicy(properties: OutboxProperties): OutboxRetryPolicy =
        OutboxRetryPolicyFactory.createDefault(name = properties.retry.policy, retryProperties = properties.retry)

    /**
     * Dispatcher that invokes the appropriate handler for each record.
     *
     * Routes records to handlers based on handler ID stored in record metadata.
     * Handles both typed and generic handlers with correct parameter passing.
     *
     * @param outboxHandlerRegistry Registry of all discovered handlers
     * @return OutboxHandlerInvoker for record dispatching
     */
    @Bean
    @ConditionalOnMissingBean
    fun outboxHandlerInvoker(outboxHandlerRegistry: OutboxHandlerRegistry): OutboxHandlerInvoker =
        OutboxHandlerInvoker(outboxHandlerRegistry)

    /**
     * Invoker for fallback handlers when record processing permanently fails.
     *
     * Routes failed records to their registered fallback handlers based on handler ID.
     * If no fallback is registered for a handler, returns silently without error.
     *
     * @param outboxFallbackHandlerRegistry Registry of registered fallback handlers
     * @return OutboxFallbackHandlerInvoker for fallback dispatching
     */
    @Bean
    @ConditionalOnMissingBean
    fun outboxFallbackHandlerInvoker(
        outboxFallbackHandlerRegistry: OutboxFallbackHandlerRegistry,
    ): OutboxFallbackHandlerInvoker = OutboxFallbackHandlerInvoker(outboxFallbackHandlerRegistry)

    /**
     * Creates the public Outbox API for scheduling records.
     *
     * Responsible for:
     * - Storing records atomically with business transactions
     * - Collecting context via OutboxContextCollector and storing it
     * - Selecting appropriate handler and storing handler ID
     * - Triggering immediate processing if configured
     *
     * @param outboxContextCollector Collector that gathers context from all registered providers
     * @param handlerRegistry Registry for handler lookup and ID assignment
     * @param recordRepository Repository for persisting records
     * @param clock Clock for timestamp generation
     * @return OutboxService implementing the Outbox interface
     */
    @Bean
    @ConditionalOnMissingBean
    internal fun outbox(
        outboxContextCollector: OutboxContextCollector,
        handlerRegistry: OutboxHandlerRegistry,
        recordRepository: OutboxRecordRepository,
        clock: Clock,
    ): Outbox =
        OutboxService(
            contextCollector = outboxContextCollector,
            handlerRegistry = handlerRegistry,
            outboxRecordRepository = recordRepository,
            clock = clock,
        )

    /**
     * Creates the context collector for gathering outbox context during record creation.
     *
     * The collector aggregates context from all registered OutboxContextProvider beans.
     * Context can include tracing information, user identity, tenant data, or any custom
     * metadata that should be captured when scheduling outbox records.
     *
     * Providers are automatically discovered and injected by Spring. The collected context
     * is stored with each outbox record for use during processing.
     *
     * @param providers List of all OutboxContextProvider beans available in the application context
     * @return OutboxContextCollector that aggregates context from all providers
     */
    @Bean
    @ConditionalOnMissingBean
    fun outboxContextCollector(providers: List<OutboxContextProvider>): OutboxContextCollector =
        OutboxContextCollector(
            providers = providers,
        )

    /**
     * Registry for managing active instances in a distributed deployment.
     *
     * Tracks which instances are alive and their partition assignments.
     * Used by PartitionCoordinator for rebalancing decisions.
     *
     * @param instanceRepository Repository for persisting instance metadata
     * @param properties Configuration properties
     * @param clock Clock for heartbeat and timeout calculations
     * @return OutboxInstanceRegistry for instance coordination
     */
    @Bean
    @ConditionalOnMissingBean
    fun outboxInstanceRegistry(
        instanceRepository: OutboxInstanceRepository,
        properties: OutboxProperties,
        clock: Clock,
    ): OutboxInstanceRegistry = OutboxInstanceRegistry(instanceRepository, properties, clock)

    /**
     * Coordinator for partition assignments in distributed deployments.
     *
     * Responsible for:
     * - Detecting instance failures and reassigning partitions
     * - Balancing partitions across active instances
     * - Preventing concurrent processing of same partition
     *
     * @param instanceRegistry Registry of active instances
     * @param partitionAssignmentRepository Repository for assignment persistence
     * @param clock Clock for timeout detection
     * @return PartitionCoordinator for managing partition lifecycle
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
     * Creates the processor chain for handling outbox records.
     *
     * Chain order:
     * 1. Primary: Dispatches records to their handlers
     * 2. Retry: Schedules retries for retryable failures
     * 3. Fallback: Invokes fallback handlers for permanent failures
     * 4. PermanentFailure: Marks records as FAILED when all else fails
     *
     * Each processor can handle the record or pass it to the next processor in the chain.
     *
     * @param handlerInvoker Dispatcher for handlers
     * @param fallbackHandlerInvoker Dispatcher for fallback handlers
     * @param recordRepository Repository for persisting record state
     * @param retryPolicyRegistry Registry for retry policies
     * @param properties Configuration properties
     * @param clock Clock for time calculations
     * @return Root processor of the chain (PrimaryOutboxRecordProcessor)
     */
    @Bean
    @ConditionalOnMissingBean
    fun outboxRecordProcessorChain(
        handlerInvoker: OutboxHandlerInvoker,
        fallbackHandlerInvoker: OutboxFallbackHandlerInvoker,
        recordRepository: OutboxRecordRepository,
        retryPolicyRegistry: OutboxRetryPolicyRegistry,
        properties: OutboxProperties,
        clock: Clock,
    ): OutboxRecordProcessor {
        val primary = PrimaryOutboxRecordProcessor(handlerInvoker, recordRepository, properties, clock)
        val retry = RetryOutboxRecordProcessor(retryPolicyRegistry, recordRepository, clock)
        val fallback =
            FallbackOutboxRecordProcessor(
                recordRepository = recordRepository,
                fallbackHandlerInvoker = fallbackHandlerInvoker,
                retryPolicyRegistry = retryPolicyRegistry,
                properties = properties,
                clock = clock,
            )
        val permanentFailure = PermanentFailureOutboxRecordProcessor(recordRepository)

        primary
            .setNext(retry)
            .setNext(fallback)
            .setNext(permanentFailure)

        return primary
    }

    /**
     * Scheduler for outbox record processing with partition coordination.
     *
     * Loads record keys from assigned partitions in batches and dispatches them
     * for parallel processing. Each record is processed through the processor chain
     * which handles dispatching, retry logic, fallback invocation, and failure marking.
     *
     * @param recordRepository Repository for loading records
     * @param recordProcessorChain Root processor of the chain (PrimaryOutboxRecordProcessor)
     * @param partitionCoordinator Coordinator for partition assignments
     * @param properties Configuration properties
     * @param taskExecutor TaskExecutor for parallel processing
     * @param clock Clock for time calculations
     * @return OutboxProcessingScheduler for coordinated processing
     */
    @Bean
    @ConditionalOnMissingBean
    fun outboxProcessingScheduler(
        recordRepository: OutboxRecordRepository,
        recordProcessorChain: OutboxRecordProcessor,
        partitionCoordinator: PartitionCoordinator,
        properties: OutboxProperties,
        @Qualifier("outboxTaskExecutor") taskExecutor: TaskExecutor,
        clock: Clock,
    ): OutboxProcessingScheduler =
        OutboxProcessingScheduler(
            recordRepository = recordRepository,
            recordProcessorChain = recordProcessorChain,
            partitionCoordinator = partitionCoordinator,
            taskExecutor = taskExecutor,
            properties = properties,
            clock = clock,
        )

    /**
     * Custom ApplicationEventMulticaster for outbox event handling.
     *
     * Intercepts specific events and routes them through the outbox system
     * instead of direct in-process publishing.
     *
     * ## Configuration
     *
     * Enabled by default. To disable:
     * ```properties
     * outbox.multicaster.enabled=false
     * ```
     *
     * @param outbox Outbox service for scheduling events
     * @param beanFactory Factory for creating the delegate multicaster
     * @param outboxProperties Configuration properties
     * @return OutboxEventMulticaster as the applicationEventMulticaster bean
     */
    @Bean(name = ["applicationEventMulticaster"])
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = ["outbox.multicaster.enabled"], havingValue = "true", matchIfMissing = true)
    fun outboxApplicationEventMulticaster(
        outbox: Outbox,
        beanFactory: BeanFactory,
        outboxProperties: OutboxProperties,
    ): OutboxEventMulticaster =
        OutboxEventMulticaster(
            outbox = outbox,
            outboxProperties = outboxProperties,
            delegateEventMulticaster = SimpleApplicationEventMulticaster(beanFactory),
        )

    companion object {
        /**
         * Registry that stores all discovered handler methods.
         *
         * Maintains three indexes:
         * - By ID: Direct lookup for dispatching
         * - By Type: Typed handlers for specific payload types
         * - Generic: Handlers accepting any payload type
         *
         * Populated by OutboxHandlerBeanPostProcessor during bean initialization.
         *
         * Marked as ROLE_INFRASTRUCTURE to indicate this is a framework bean that
         * should not be processed by user-level BeanPostProcessors.
         *
         * @return Empty OutboxHandlerRegistry (populated during bean post-processing)
         */
        @Bean
        @ConditionalOnMissingBean
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        @JvmStatic
        internal fun outboxHandlerRegistry(): OutboxHandlerRegistry = OutboxHandlerRegistry()

        /**
         * Registry for fallback handlers with 1:1 mapping to their handlers.
         *
         * Each handler can have at most one fallback handler. Fallback handlers are invoked
         * when the main handler fails after all retries are exhausted or on non-retryable exceptions.
         *
         * Populated during bean post-processing.
         *
         * @return Empty OutboxFallbackHandlerRegistry (populated during bean post-processing)
         */
        @Bean
        @ConditionalOnMissingBean
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        @JvmStatic
        internal fun outboxFallbackHandlerRegistry(): OutboxFallbackHandlerRegistry = OutboxFallbackHandlerRegistry()

        /**
         * Registry for handler-specific retry policies.
         *
         * Manages the retry policy for each handler method. Policies are resolved
         * during handler registration using:
         * 1. @OutboxRetryable annotation (loads policy bean by name)
         * 2. OutboxRetryAware interface (uses policy from handler)
         * 3. Default retry policy as fallback
         *
         * @param beanFactory Spring bean factory for loading policy beans by name
         * @return OutboxRetryPolicyRegistry for managing handler-specific policies
         */
        @Bean
        @ConditionalOnMissingBean
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        @JvmStatic
        internal fun outboxRetryPolicyRegistry(beanFactory: BeanFactory): OutboxRetryPolicyRegistry =
            OutboxRetryPolicyRegistry(beanFactory)

        /**
         * BeanPostProcessor that discovers and registers handlers and fallbacks.
         *
         * Scans for @OutboxHandler/@OutboxFallbackHandler annotations and interface implementations.
         * Registers handlers, fallbacks, and retry policies during bean initialization.
         *
         * @param handlerRegistry Handler registry to populate
         * @param fallbackHandlerRegistry Fallback handler registry to populate
         * @param retryPolicyRegistry Retry policy registry to populate
         * @return OutboxHandlerBeanPostProcessor bean
         */
        @Bean
        @ConditionalOnMissingBean
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        @JvmStatic
        internal fun outboxHandlerBeanPostProcessor(
            handlerRegistry: OutboxHandlerRegistry,
            fallbackHandlerRegistry: OutboxFallbackHandlerRegistry,
            retryPolicyRegistry: OutboxRetryPolicyRegistry,
        ): OutboxHandlerBeanPostProcessor =
            OutboxHandlerBeanPostProcessor(handlerRegistry, fallbackHandlerRegistry, retryPolicyRegistry)
    }
}
