package io.namastack.outbox.runtime

import io.namastack.outbox.OutboxProcessingScheduler
import io.namastack.outbox.OutboxService
import io.namastack.outbox.handler.OutboxHandlerInfrastructure
import io.namastack.outbox.instance.OutboxInstanceRegistry
import io.namastack.outbox.partition.PartitionAssignmentCache
import io.namastack.outbox.partition.PartitionCoordinator
import io.namastack.outbox.processor.FallbackOutboxRecordProcessor
import io.namastack.outbox.processor.OutboxRecordProcessor
import io.namastack.outbox.processor.OutboxRecordProcessorChainInvoker
import io.namastack.outbox.processor.PermanentFailureOutboxRecordProcessor
import io.namastack.outbox.processor.PrimaryOutboxRecordProcessor
import io.namastack.outbox.processor.RetryOutboxRecordProcessor
import io.namastack.outbox.trigger.OutboxPollingTriggerFactory

/**
 * Constructs one stopped outbox runtime from fully resolved inputs.
 *
 * The factory performs no Spring bean lookup, persistence selection, or lifecycle startup.
 *
 * @author Roland Beisel
 * @since 1.10.0
 */
object OutboxRuntimeFactory {
    /**
     * Creates one isolated runtime without starting it.
     *
     * @param spec Fully resolved inputs for the runtime
     * @return Stopped runtime ready for handler use and explicit startup
     * @throws IllegalStateException if the polling configuration is unsupported
     */
    fun create(spec: OutboxRuntimeSpec): OutboxRuntime {
        val persistence = spec.persistence
        val handlers = spec.handlerInfrastructure
        val resources = spec.resources

        val outbox =
            OutboxService(
                contextCollector = spec.contextCollector,
                handlerRegistry = handlers.handlerRegistry,
                outboxRecordRepository = persistence.recordRepository,
                clock = spec.clock,
                instrumentationSupplier = { handlers.instrumentation },
                channelNameProviderSupplier = { handlers.channelNameProvider },
            )
        val instanceRegistry =
            OutboxInstanceRegistry(
                instanceRepository = persistence.instanceRepository,
                settings = spec.settings.instance,
                clock = spec.clock,
                taskScheduler = resources.heartbeatScheduler,
                observationRegistry = { spec.observationRegistry },
            )
        val partitionCoordinator =
            PartitionCoordinator(
                instanceRegistry = instanceRegistry,
                partitionAssignmentRepository = persistence.partitionAssignmentRepository,
                partitionAssignmentCache = PartitionAssignmentCache(persistence.partitionAssignmentRepository),
                clock = spec.clock,
                taskScheduler = resources.taskScheduler,
                rebalanceInterval = spec.settings.instance.rebalanceInterval,
                observationRegistry = { spec.observationRegistry },
            )
        val processorChain = createProcessorChain(handlers, spec)
        val processorChainInvoker =
            OutboxRecordProcessorChainInvoker(
                recordProcessorChain = processorChain,
                instrumentationSupplier = { handlers.instrumentation },
                channelNameProviderSupplier = { handlers.channelNameProvider },
            )
        val processingScheduler =
            OutboxProcessingScheduler(
                trigger = OutboxPollingTriggerFactory.create(spec.settings.polling, spec.clock),
                taskScheduler = resources.taskScheduler,
                observationRegistry = { spec.observationRegistry },
                recordRepository = persistence.recordRepository,
                recordProcessorChainInvoker = processorChainInvoker,
                partitionCoordinator = partitionCoordinator,
                taskExecutor = resources.taskExecutor,
                settings = spec.settings,
                clock = spec.clock,
            )
        return OutboxRuntime(
            outbox = outbox,
            lifecycleComponents = listOf(instanceRegistry, partitionCoordinator, processingScheduler),
        )
    }

    private fun createProcessorChain(
        handlers: OutboxHandlerInfrastructure,
        spec: OutboxRuntimeSpec,
    ): OutboxRecordProcessor {
        val repository = spec.persistence.recordRepository
        val processing = spec.settings.processing
        val primary = PrimaryOutboxRecordProcessor(handlers.handlerInvoker, repository, processing, spec.clock)
        val retry = RetryOutboxRecordProcessor(handlers.retryPolicyRegistry, repository, spec.clock)
        val fallback =
            FallbackOutboxRecordProcessor(
                recordRepository = repository,
                fallbackHandlerRegistry = handlers.fallbackHandlerRegistry,
                fallbackHandlerInvoker = handlers.fallbackHandlerInvoker,
                settings = processing,
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
