package io.namastack.outbox.dispatch

import io.namastack.outbox.OutboxProcessingScheduler
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.OutboxRecordRepository.Companion.MAX_RECORD_KEYS_PER_FETCH
import io.namastack.outbox.runtime.OutboxRuntimeSettings
import org.slf4j.LoggerFactory
import org.springframework.core.task.TaskExecutor
import org.springframework.core.task.support.TaskExecutorAdapter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/**
 * Dispatches one batch of record keys per run.
 *
 * A run selects up to [OutboxRuntimeSettings.Polling.batchSize] record keys, submits one task per key,
 * and waits for every task to finish before it returns.
 *
 * This type is part of the processing runtime. It is not intended to be used by applications.
 *
 * @param recordRepository Repository for loading record keys and records
 * @param keyProcessor Processor for the records of one key
 * @param taskExecutor Executor for parallel key processing
 * @param settings Effective runtime settings
 *
 * @author Aleksander Zamojski
 * @since 2.0.0
 */
class BatchKeyDispatcher(
    private val recordRepository: OutboxRecordRepository,
    private val keyProcessor: OutboxRecordKeyProcessor,
    private val taskExecutor: TaskExecutor,
    private val settings: OutboxRuntimeSettings,
) : KeyDispatcher {
    // Processing log output uses the scheduler's logger category.
    private val log = LoggerFactory.getLogger(OutboxProcessingScheduler::class.java)

    private val asyncTaskExecutor = TaskExecutorAdapter(taskExecutor)

    override val reportsPollResults: Boolean = false

    override fun dispatch(
        partitions: Set<Int>,
        isStopping: () -> Boolean,
    ): Int {
        val recordKeys = loadRecordKeys(partitions)
        if (recordKeys.isEmpty()) return 0

        log.debug("Found {} record keys to process", recordKeys.size)
        processBatch(recordKeys)
        log.debug("Finished processing {} record keys", recordKeys.size)

        return recordKeys.size
    }

    override fun isCompatibilityCooldownActive(): Boolean = keyProcessor.isCompatibilityCooldownActive()

    private fun loadRecordKeys(partitions: Set<Int>): List<String> =
        recordRepository.findRecordKeysInPartitions(
            partitions = partitions,
            batchSize = settings.polling.batchSize,
            ignoreRecordKeysWithPreviousFailure = settings.processing.stopOnFirstFailure,
        )

    private fun processBatch(recordKeys: List<String>) {
        val completions = ArrayList<CompletableFuture<*>>(recordKeys.size)
        try {
            recordKeys.chunked(MAX_RECORD_KEYS_PER_FETCH).forEach { keys ->
                val pages = recordRepository.findIncompleteRecordPages(keys.toSet())
                keys.forEach { key ->
                    val page = pages.getValue(key)
                    val completion =
                        asyncTaskExecutor
                            .submitCompletable { keyProcessor.processRecordKey(key, page) }
                            .exceptionally { failure ->
                                val cause = (failure as? CompletionException)?.cause ?: failure
                                log.error("Error processing key {}", key, cause)
                                null
                            }
                    completions.add(completion)
                }
            }
        } finally {
            // Failed fetches, rejected submissions and interrupts must not release active assignments.
            CompletableFuture.allOf(*completions.toTypedArray()).join()
        }
    }
}
