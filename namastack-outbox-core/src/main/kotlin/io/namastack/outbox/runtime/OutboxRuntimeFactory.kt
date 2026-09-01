package io.namastack.outbox.runtime

import io.namastack.outbox.OutboxProcessingScheduler
import io.namastack.outbox.OutboxService
import io.namastack.outbox.handler.OutboxHandlerInfrastructure
import io.namastack.outbox.instance.OutboxInstanceRegistry
import io.namastack.outbox.partition.PartitionAssignmentCache
import io.namastack.outbox.partition.PartitionCoordinator
import io.namastack.outbox.processor.FallbackOutboxRecordProcessor
import io.namastack.outbox.processor.OutboxRecordProcessor
import io.namastack.outbox.processor.PermanentFailureOutboxRecordProcessor
import io.namastack.outbox.processor.PrimaryOutboxRecordProcessor
import io.namastack.outbox.processor.RetryOutboxRecordProcessor
import io.namastack.outbox.trigger.OutboxPollingTriggerFactory

/**
 * Constructs one complete programmatic outbox runtime from fully resolved inputs.
 *
 * The factory has no Spring bean lookup, configuration binding, persistence selection, lifecycle
 * side effects, or knowledge of other runtimes.
 *
 * @author Roland Beisel
 * @since 1.10.0
 */
object OutboxRuntimeFactory {
    /**
     * Creates one assembled but not yet started runtime.
     *
     * @param spec Fully resolved runtime inputs
     * @return Isolated runtime ready for handler registration and explicit startup
     * @throws IllegalStateException if polling configuration is unsupported
     */
    fun create(spec: OutboxRuntimeSpec): OutboxRuntime {
        val persistence = spec.persistence
        val handlers = spec.handlerInfrastructure
        val properties = spec.properties
        val resources = spec.resources

        val outbox =
            OutboxService(
                contextCollector = spec.contextCollector,
                handlerRegistry = handlers.handlerRegistry,
                outboxRecordRepository = persistence.recordRepository,
                clock = spec.clock,
                instrumentation = spec.instrumentation,
                channelNameProvider = spec.channelNameProvider,
            )
        val instanceRegistry =
            OutboxInstanceRegistry(
                instanceRepository = persistence.instanceRepository,
                properties = properties,
                clock = spec.clock,
                taskScheduler = resources.heartbeatScheduler,
                observationRegistry = spec.observationRegistry,
            )
        val partitionCoordinator =
            PartitionCoordinator(
                instanceRegistry = instanceRegistry,
                partitionAssignmentRepository = persistence.partitionAssignmentRepository,
                partitionAssignmentCache = PartitionAssignmentCache(persistence.partitionAssignmentRepository),
                clock = spec.clock,
            )
        val processorChain = createProcessorChain(handlers, spec)
        val trigger = OutboxPollingTriggerFactory.create(properties, spec.clock)
        val processingScheduler =
            OutboxProcessingScheduler(
                trigger = trigger,
                taskScheduler = resources.taskScheduler,
                observationRegistry = spec.observationRegistry,
                recordRepository = persistence.recordRepository,
                recordProcessorChain = processorChain,
                partitionCoordinator = partitionCoordinator,
                taskExecutor = resources.taskExecutor,
                properties = properties,
                clock = spec.clock,
            )

        return OutboxRuntime(
            outbox = outbox,
            instanceRegistry = instanceRegistry,
            partitionCoordinator = partitionCoordinator,
            processingScheduler = processingScheduler,
            taskScheduler = resources.taskScheduler,
            rebalanceInterval = properties.effectiveRebalanceInterval,
            observationRegistry = spec.observationRegistry,
            ownedPersistenceResources = persistence.ownedResources,
            ownedThreadingResources = resources.ownedResources,
        )
    }

    private fun createProcessorChain(
        handlers: OutboxHandlerInfrastructure,
        spec: OutboxRuntimeSpec,
    ): OutboxRecordProcessor {
        val repository = spec.persistence.recordRepository
        val primary = PrimaryOutboxRecordProcessor(handlers.handlerInvoker, repository, spec.properties, spec.clock)
        val retry = RetryOutboxRecordProcessor(handlers.retryPolicyRegistry, repository, spec.clock)
        val fallback =
            FallbackOutboxRecordProcessor(
                recordRepository = repository,
                fallbackHandlerRegistry = handlers.fallbackHandlerRegistry,
                fallbackHandlerInvoker = handlers.fallbackHandlerInvoker,
                properties = spec.properties,
                clock = spec.clock,
            )
        val permanentFailure = PermanentFailureOutboxRecordProcessor(repository)

        primary
            .setNext(retry)
            .setNext(fallback)
            .setNext(permanentFailure)

        return primary
    }
}
