package io.namastack.outbox.dispatch

import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.processor.OutboxRecordProcessorChainInvoker
import io.namastack.outbox.runtime.OutboxRuntimeSettings
import io.namastack.outbox.runtime.OutboxRuntimeSettings.Processing.DispatchMode
import io.namastack.outbox.trigger.OutboxPollingTrigger
import org.springframework.core.task.TaskExecutor
import java.time.Clock

/**
 * Factory for creating [KeyDispatcher] instances based on effective runtime settings.
 *
 * - [DispatchMode.BATCH]: Creates a [BatchKeyDispatcher]
 * - [DispatchMode.CONTINUOUS]: Creates a [ContinuousKeyDispatcher]
 *
 * @author Aleksander Zamojski
 * @since 2.0.0
 */
internal object OutboxKeyDispatcherFactory {
    /**
     * Creates the dispatcher selected by [OutboxRuntimeSettings.Processing.dispatchMode].
     *
     * @param settings Effective runtime settings
     * @param trigger Polling trigger of the scheduler that uses the dispatcher
     * @param recordRepository Repository for loading record keys and records
     * @param recordProcessorChainInvoker Invoker for the instrumented record processor chain
     * @param taskExecutor Executor for parallel key processing
     * @param clock Clock for time calculations
     * @return The configured key dispatcher
     */
    fun create(
        settings: OutboxRuntimeSettings,
        trigger: OutboxPollingTrigger,
        recordRepository: OutboxRecordRepository,
        recordProcessorChainInvoker: OutboxRecordProcessorChainInvoker,
        taskExecutor: TaskExecutor,
        clock: Clock,
    ): KeyDispatcher {
        val keyProcessor = OutboxRecordKeyProcessor(recordProcessorChainInvoker, settings.processing, clock)
        return when (settings.processing.dispatchMode) {
            DispatchMode.BATCH -> {
                BatchKeyDispatcher(
                    recordRepository = recordRepository,
                    keyProcessor = keyProcessor,
                    taskExecutor = taskExecutor,
                    settings = settings,
                    trigger = trigger,
                )
            }

            DispatchMode.CONTINUOUS -> {
                ContinuousKeyDispatcher(
                    recordRepository = recordRepository,
                    keyProcessor = keyProcessor,
                    taskExecutor = taskExecutor,
                    settings = settings,
                    trigger = trigger,
                    clock = clock,
                )
            }
        }
    }
}
