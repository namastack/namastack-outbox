package io.namastack.outbox

import io.namastack.outbox.annotation.EnableOutbox
import io.namastack.outbox.handler.OutboxHandlerBeanPostProcessor
import io.namastack.outbox.handler.OutboxHandlerInvoker
import io.namastack.outbox.handler.OutboxHandlerRegistry
import io.namastack.outbox.instance.OutboxInstanceRegistry
import io.namastack.outbox.instance.OutboxInstanceRepository
import io.namastack.outbox.partition.PartitionAssignmentRepository
import io.namastack.outbox.partition.PartitionCoordinator
import io.namastack.outbox.retry.OutboxRetryPolicy
import io.namastack.outbox.retry.OutboxRetryPolicyFactory
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurationPackage
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
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
 * Activated when @EnableOutbox annotation is present. Provides all necessary beans
 * for the outbox pattern implementation including:
 * - Handler discovery and registration (BeanPostProcessor)
 * - Partition-aware processing scheduling
 * - Retry policies and recovery strategies
 * - Instance coordination for distributed deployments
 * - Event multicasting for @OutboxEvent annotated events
 *
 * ## Bean Groups
 *
 * **Core Infrastructure:**
 * - clock, task executors, schedulers
 *
 * **Handler Management:**
 * - OutboxHandlerRegistry, OutboxHandlerBeanPostProcessor
 * - OutboxHandlerInvoker for dispatching to handlers
 *
 * **Record Processing:**
 * - OutboxProcessingScheduler (partition-aware)
 * - PartitionCoordinator for distributed coordination
 * - OutboxRetryPolicy for failure handling
 *
 * **Event Publishing (Optional):**
 * - OutboxEventMulticaster (if outbox.multicaster.enabled=true)
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@AutoConfiguration
@AutoConfigurationPackage
@ConditionalOnBean(annotation = [EnableOutbox::class])
@EnableConfigurationProperties(OutboxProperties::class)
class OutboxCoreAutoConfiguration {
    // ============================================================
    // INFRASTRUCTURE BEANS
    // ============================================================

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
     * Scheduler for general outbox batch processing tasks.
     *
     * Pool size: 5 (internal use only)
     * Used for periodic batch operations and scheduled maintenance.
     *
     * @return ThreadPoolTaskScheduler for outbox batch jobs
     */
    @Bean("outboxDefaultScheduler")
    @ConditionalOnMissingBean(name = ["outboxDefaultScheduler"])
    fun outboxDefaultScheduler(): ThreadPoolTaskScheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = 5
            setThreadNamePrefix("outbox-scheduler-")
            setWaitForTasksToCompleteOnShutdown(true)
            initialize()
        }

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
    fun outboxRebalancingScheduler(): ThreadPoolTaskScheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = 1
            setThreadNamePrefix("outbox-rebalancing-")
            setWaitForTasksToCompleteOnShutdown(true)
            initialize()
        }

    // ============================================================
    // RETRY & FAILURE HANDLING
    // ============================================================

    /**
     * Creates the retry policy for failed record processing.
     *
     * Policy type is configurable via outbox.retry.policy:
     * - exponential: Exponential backoff with jitter
     * - fixed: Fixed delay between retries
     * - linear: Linear backoff
     *
     * @param properties Outbox configuration including retry settings
     * @return Configured OutboxRetryPolicy
     */
    @Bean
    @ConditionalOnMissingBean
    fun retryPolicy(properties: OutboxProperties): OutboxRetryPolicy =
        OutboxRetryPolicyFactory.create(name = properties.retry.policy, retryProperties = properties.retry)

    // ============================================================
    // HANDLER DISCOVERY & DISPATCHING
    // ============================================================

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

    // ============================================================
    // CORE OUTBOX SERVICE
    // ============================================================

    /**
     * Creates the public Outbox API for scheduling records.
     *
     * Responsible for:
     * - Storing records atomically with business transactions
     * - Selecting appropriate handler and storing handler ID
     * - Triggering immediate processing if configured
     *
     * @param handlerRegistry Registry for handler lookup and ID assignment
     * @param recordRepository Repository for persisting records
     * @param clock Clock for timestamp generation
     * @return OutboxService implementing the Outbox interface
     */
    @Bean
    @ConditionalOnMissingBean
    internal fun outbox(
        handlerRegistry: OutboxHandlerRegistry,
        recordRepository: OutboxRecordRepository,
        clock: Clock,
    ): Outbox =
        OutboxService(
            handlerRegistry = handlerRegistry,
            outboxRecordRepository = recordRepository,
            clock = clock,
        )

    // ============================================================
    // DISTRIBUTED COORDINATION
    // ============================================================

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

    // ============================================================
    // RECORD PROCESSING
    // ============================================================

    /**
     * Main scheduler for processing outbox records.
     *
     * Responsibilities:
     * - Discovers pending records by partition
     * - Routes records to handlers via OutboxHandlerInvoker
     * - Implements retry logic with configurable backoff
     * - Marks records as completed or failed
     * - Supports ordered processing per record key (partition-based)
     *
     * ## Processing Flow
     *
     * 1. Get assigned partitions from PartitionCoordinator
     * 2. Load pending record keys from each partition
     * 3. For each record key:
     *    a. Load all incomplete records in order
     *    b. Dispatch each to the handler
     *    c. On failure: Apply retry logic (delay + increment counter)
     *    d. On success: Mark completed and optionally delete
     *
     * ## Concurrency
     *
     * - Multiple record keys are processed in parallel (via taskExecutor)
     * - Records within same key are processed sequentially (ordering guarantee)
     * - One instance processes one partition (no concurrent access)
     *
     * @param recordRepository Repository for accessing/updating records
     * @param dispatcher Dispatcher that invokes handlers
     * @param partitionCoordinator Coordinator for partition assignments
     * @param retryPolicy Policy for determining retry behavior
     * @param properties Configuration properties
     * @param taskExecutor TaskExecutor for parallel key processing
     * @param clock Clock for time-based calculations
     * @return OutboxProcessingScheduler for orchestrating record processing
     */
    @Bean
    @ConditionalOnMissingBean
    fun outboxProcessingScheduler(
        recordRepository: OutboxRecordRepository,
        dispatcher: OutboxHandlerInvoker,
        partitionCoordinator: PartitionCoordinator,
        retryPolicy: OutboxRetryPolicy,
        properties: OutboxProperties,
        @Qualifier("outboxTaskExecutor") taskExecutor: TaskExecutor,
        clock: Clock,
    ): OutboxProcessingScheduler =
        OutboxProcessingScheduler(
            recordRepository = recordRepository,
            handlerInvoker = dispatcher,
            partitionCoordinator = partitionCoordinator,
            retryPolicy = retryPolicy,
            properties = properties,
            taskExecutor = taskExecutor,
            clock = clock,
        )

    // ============================================================
    // EVENT PUBLISHING (OPTIONAL)
    // ============================================================

    /**
     * Custom ApplicationEventMulticaster for @OutboxEvent handling.
     *
     * Intercepts events annotated with @OutboxEvent and routes them through
     * the outbox system instead of direct in-process publishing.
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
         * BeanPostProcessor that discovers and registers handler methods.
         *
         * Scans each bean for:
         * 1. @OutboxHandler annotated methods
         * 2. OutboxHandler/OutboxTypedHandler<T> interface implementations
         *
         * Supports both typed and generic handler signatures.
         *
         * Marked as ROLE_INFRASTRUCTURE to prevent circular dependency issues.
         * This ensures the AutoConfiguration bean is not processed by this BeanPostProcessor.
         *
         * @param registry The handler registry to populate
         * @return OutboxHandlerBeanPostProcessor bean
         */
        @Bean
        @ConditionalOnMissingBean
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        @JvmStatic
        internal fun outboxHandlerBeanPostProcessor(registry: OutboxHandlerRegistry): OutboxHandlerBeanPostProcessor =
            OutboxHandlerBeanPostProcessor(registry)
    }
}
