package io.namastack.outbox.dispatch

import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.OutboxRecordRepository.Companion.MAX_RECORD_KEYS_PER_FETCH
import io.namastack.outbox.runtime.OutboxRuntimeSettings
import io.namastack.outbox.trigger.OutboxPollingTrigger
import org.slf4j.LoggerFactory
import org.springframework.core.task.AsyncTaskExecutor
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/**
 * Dispatches one batch of record keys per run.
 *
 * A run selects up to [OutboxRuntimeSettings.Polling.batchSize] record keys, submits one task per key,
 * and waits for every task to finish before it returns. The number of selected keys is reported to
 * the polling trigger against the batch size; a failed key query is reported as zero keys found.
 *
 * @param recordRepository Repository for loading record keys and records
 * @param keyProcessor Processor for the records of one key
 * @param taskExecutor Executor for parallel key processing
 * @param settings Effective runtime settings
 * @param trigger Polling trigger that receives poll results
 *
 * @author Aleksander Zamojski
 * @since 2.0.0
 */
internal class BatchKeyDispatcher(
    private val recordRepository: OutboxRecordRepository,
    private val keyProcessor: OutboxRecordKeyProcessor,
    private val taskExecutor: AsyncTaskExecutor,
    private val settings: OutboxRuntimeSettings,
    private val trigger: OutboxPollingTrigger,
) : OutboxKeyDispatcher {
    private val log = LoggerFactory.getLogger(BatchKeyDispatcher::class.java)

    private val batchSize: Int = settings.polling.batchSize

    override fun dispatch(
        partitions: Set<Int>,
        shouldStop: () -> Boolean,
    ) {
        val recordKeys = queryRecordKeys(partitions)
        trigger.onPollResult(found = recordKeys.size, requested = batchSize)
        if (recordKeys.isEmpty()) return

        log.debug("Found {} record keys to process", recordKeys.size)
        processBatch(recordKeys)
        log.debug("Finished processing {} record keys", recordKeys.size)
    }

    override fun isCompatibilityCooldownActive(): Boolean = keyProcessor.isCompatibilityCooldownActive()

    /**
     * Queries record keys of the given partitions.
     *
     * @return record keys to process, or an empty list if the query failed
     */
    private fun queryRecordKeys(partitions: Set<Int>): List<String> =
        try {
            recordRepository.findRecordKeysInPartitions(
                partitions = partitions,
                batchSize = batchSize,
                ignoreRecordKeysWithPreviousFailure = settings.processing.stopOnFirstFailure,
            )
        } catch (ex: Exception) {
            log.error("Error querying record keys; skipping this run", ex)
            emptyList()
        }

    private fun processBatch(recordKeys: List<String>) {
        val completions = ArrayList<CompletableFuture<*>>(recordKeys.size)
        try {
            recordKeys.chunked(MAX_RECORD_KEYS_PER_FETCH).forEach { chunk ->
                val pages = recordRepository.findIncompleteRecordPages(chunk.toSet())
                chunk.forEach { key ->
                    val page = pages.getValue(key)
                    val completion =
                        taskExecutor
                            .submitCompletable { keyProcessor.process(key, page) }
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
