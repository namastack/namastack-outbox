package io.namastack.outbox.runtime

import io.namastack.outbox.OutboxProcessingScheduler
import io.namastack.outbox.OutboxService
import io.namastack.outbox.completion.OutboxCompletionBatcher
import io.namastack.outbox.completion.OutboxCompletionBatchingPersistence
import io.namastack.outbox.completion.OutboxCompletionDirectPersistence
import io.namastack.outbox.completion.OutboxCompletionPersistence
import io.namastack.outbox.completion.OutboxRecordCompleter
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
 * The factory performs no Spring bean lookup or lifecycle startup. It uses the repositories
 * supplied in [OutboxRuntimeSpec] and assembles completion persistence against those repositories.
 *
 * @author Roland Beisel
 * @since 1.10.0
 */
object OutboxRuntimeFactory {
    /**
     * Creates one isolated runtime without starting it.
     *
     * Construction starts no workers. Closing the runtime stops completion workers without closing
     * their executor. Accepted writes may outlive the configured shutdown wait and still require
     * the borrowed executor and persistence resources.
     *
     * @param spec Fully resolved inputs for the runtime
     * @return Stopped runtime ready for handler use and explicit startup
     * @throws IllegalArgumentException if batching is enabled without a persistence writer or completion executor
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
                processingGroups = spec.settings.processingGroups,
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
        val batcher = createCompletionBatcher(spec)
        val completionPersistence = createCompletionPersistence(persistence, batcher)
        val processorChain = createProcessorChain(handlers, spec, completionPersistence)
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
            lifecycleComponents =
                listOfNotNull(
                    instanceRegistry,
                    partitionCoordinator,
                    batcher,
                    processingScheduler,
                ),
        )
    }

    private fun createCompletionBatcher(spec: OutboxRuntimeSpec): OutboxCompletionBatcher? {
        val settings = spec.settings.completionBatching
        if (!settings.enabled) return null

        val writer =
            requireNotNull(spec.persistence.completionWriter) {
                "Completion batching requires a completion writer from the persistence provider"
            }
        val executor =
            requireNotNull(spec.resources.completionExecutor) {
                "Completion batching requires a completion executor"
            }

        return OutboxCompletionBatcher(
            executor = executor,
            shutdownTimeout = spec.settings.processing.shutdownTimeout,
            writer = writer,
            settings = settings,
        )
    }

    private fun createCompletionPersistence(
        persistence: OutboxRuntimePersistence,
        batcher: OutboxCompletionBatcher?,
    ): OutboxCompletionPersistence =
        if (batcher == null) {
            OutboxCompletionDirectPersistence(persistence.recordRepository)
        } else {
            OutboxCompletionBatchingPersistence(persistence.recordRepository, batcher)
        }

    private fun createProcessorChain(
        handlers: OutboxHandlerInfrastructure,
        spec: OutboxRuntimeSpec,
        completionPersistence: OutboxCompletionPersistence,
    ): OutboxRecordProcessor {
        val processing = spec.settings.processing
        val completer = OutboxRecordCompleter(completionPersistence, processing, spec.clock)
        val primary = PrimaryOutboxRecordProcessor(handlers.handlerInvoker, completer)
        val retry = RetryOutboxRecordProcessor(handlers.retryPolicyRegistry, completionPersistence, spec.clock)
        val fallback =
            FallbackOutboxRecordProcessor(
                fallbackHandlerRegistry = handlers.fallbackHandlerRegistry,
                fallbackHandlerInvoker = handlers.fallbackHandlerInvoker,
                completer = completer,
            )
        val permanentFailure = PermanentFailureOutboxRecordProcessor(completionPersistence)

        primary
            .setNext(retry)
            .setNext(fallback)
            .setNext(permanentFailure)

        return primary
    }
}
