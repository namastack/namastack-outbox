package io.namastack.outbox.dispatch

import io.namastack.outbox.OutboxProcessingScheduler
import io.namastack.outbox.OutboxRecordPage
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.OutboxRecordRepository.Companion.MAX_RECORD_KEYS_PER_FETCH
import io.namastack.outbox.runtime.OutboxRuntimeSettings
import io.namastack.outbox.trigger.OutboxPollingTrigger
import org.slf4j.LoggerFactory
import org.springframework.core.task.TaskExecutor
import org.springframework.core.task.support.TaskExecutorAdapter
import org.springframework.scheduling.support.SimpleTriggerContext
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CompletionException

/**
 * Dispatches record keys continuously within one run.
 *
 * Steps:
 * 1. Fetch batch
 * 2.
 *  - If batch is empty finished the job (step 4).
 *  - If batch is partially filled then wait for all records to finish (step 4) or timeout (step 3)
 *  - If batch is full then wait for 1/4 batch to finish (step 3) or for timeout (step 3)
 * 3. Fetch next batch. go to step 2
 * 4. Finish the job
 *
 *
 * @param recordRepository Repository for loading record keys and records
 * @param keyProcessor Processor for the records of one key
 * @param taskExecutor Executor for parallel key processing
 * @param settings Effective runtime settings
 * @param trigger Polling trigger that receives poll results and provides the wait timeout
 * @param clock Clock for wait-timeout calculations
 *
 * @author Aleksander Zamojski
 * @since 2.0.0
 */
class ContinuousKeyDispatcher(
    private val recordRepository: OutboxRecordRepository,
    private val keyProcessor: OutboxRecordKeyProcessor,
    private val taskExecutor: TaskExecutor,
    private val settings: OutboxRuntimeSettings,
    private val trigger: OutboxPollingTrigger,
    private val clock: Clock,
) : OutboxKeyDispatcher {
    private companion object {
        private val MIN_WAIT_TIMEOUT: Duration = Duration.ofMillis(1)
    }

    // Processing log output uses the scheduler's logger category.
    private val log = LoggerFactory.getLogger(OutboxProcessingScheduler::class.java)

    private val asyncTaskExecutor = TaskExecutorAdapter(taskExecutor)

    private val batchSize: Int = settings.polling.batchSize

    private val refillThreshold: Int = maxOf(1, batchSize / 4)

    override fun dispatch(
        partitions: Set<Int>,
        isStopping: () -> Boolean,
    ) = Run(partitions, isStopping).execute()

    override fun isCompatibilityCooldownActive(): Boolean = keyProcessor.isCompatibilityCooldownActive()

    /**
     * State of one dispatch run. Only the dispatching thread fetches and submits; key tasks release
     * their slot in [InFlightKeys] when they finish.
     */
    private inner class Run(
        private val partitions: Set<Int>,
        private val isStopping: () -> Boolean,
    ) {
        private val inFlightKeys = InFlightKeys(batchSize)

        /** Whether the last key query filled every free slot. Touched only by the dispatching thread. */
        private var lastBatchFull = false

        /** Written by key tasks and the dispatching thread. */
        @Volatile
        private var stopped = false

        fun execute() {
            try {
                while (shouldKeepDispatching()) {
                    submitKeys(fetchBatch())
                    if (!shouldKeepDispatching()) break
                    awaitNextFetch()
                }
            } finally {
                // Accepted key tasks must finish before the run releases its partition assignment.
                inFlightKeys.awaitIdleUninterruptibly()
            }
        }

        /**
         * Single place for every reason to stop dispatching new keys in this run.
         */
        private fun shouldKeepDispatching(): Boolean {
            if (stopped) return false
            if (Thread.currentThread().isInterrupted) {
                log.debug("Stopping continuous dispatch because the processing thread was interrupted")
                return false
            }
            if (isStopping()) {
                log.debug("Stopping continuous dispatch because the scheduler is shutting down")
                return false
            }
            if (keyProcessor.isCompatibilityCooldownActive()) {
                log.debug("Stopping continuous dispatch because a compatibility cooldown is active")
                return false
            }
            return true
        }

        /**
         * Queries record keys and keeps at most one key per free slot, skipping keys in flight.
         */
        private fun fetchBatch(): List<String> {
            val freeSlots = inFlightKeys.freeSlots
            if (freeSlots == 0) {
                // Nothing finished within the wait timeout; querying could not fill any slot.
                lastBatchFull = true
                return emptyList()
            }

            val keysInFlight = inFlightKeys.snapshot()
            val batch =
                queryRecordKeys()
                    .asSequence()
                    .filterNot { it in keysInFlight }
                    .take(freeSlots)
                    .toList()

            lastBatchFull = batch.size == freeSlots
            trigger.onPollResult(found = batch.size, requested = freeSlots)
            if (batch.isNotEmpty()) {
                log.debug("Fetched {} record keys for {} free slots", batch.size, freeSlots)
            }
            return batch
        }

        private fun queryRecordKeys(): List<String> =
            try {
                recordRepository.findRecordKeysInPartitions(
                    partitions = partitions,
                    batchSize = batchSize,
                    ignoreRecordKeysWithPreviousFailure = settings.processing.stopOnFirstFailure,
                )
            } catch (ex: Exception) {
                log.error("Error querying record keys; stopping dispatch for this run", ex)
                stopped = true
                emptyList()
            }

        /**
         * Loads records of the selected keys and submits one task per key. On failure, stops dispatching.
         */
        private fun submitKeys(keys: List<String>) {
            try {
                keys.chunked(MAX_RECORD_KEYS_PER_FETCH).forEach { chunk ->
                    val pages = recordRepository.findIncompleteRecordPages(chunk.toSet())
                    chunk.forEach { key -> submitKey(key, pages.getValue(key)) }
                }
            } catch (ex: Exception) {
                log.error("Error dispatching record keys; stopping dispatch for this run", ex)
                stopped = true
            }
        }

        private fun submitKey(
            key: String,
            page: OutboxRecordPage,
        ) {
            check(inFlightKeys.tryReserve(key)) { "No free slot for record key '$key'" }
            try {
                asyncTaskExecutor
                    .submitCompletable { processKey(key, page) }
                    .exceptionally { failure ->
                        val cause = (failure as? CompletionException)?.cause ?: failure
                        log.error("Error processing key {}", key, cause)
                        null
                    }
            } catch (ex: Exception) {
                // The task was not accepted, so it will never release its slot.
                inFlightKeys.release(key)
                throw ex
            }
        }

        private fun processKey(
            key: String,
            page: OutboxRecordPage,
        ) {
            var succeeded = false
            try {
                succeeded = keyProcessor.process(key, page)
            } finally {
                if (!succeeded) {
                    log.debug("Stopping continuous dispatch because processing of key {} failed", key)
                    stopped = true
                }
                inFlightKeys.release(key)
            }
        }

        /**
         * Full batch: waits until the refill threshold of slots is free or the wait timeout passes.
         * Partial batch: waits until every key finished, which ends the run, or the wait timeout passes.
         */
        private fun awaitNextFetch() {
            if (!lastBatchFull && inFlightKeys.isIdle()) {
                stopped = true
                return
            }

            val timeout = waitTimeout()
            if (timeout == null) {
                log.debug("Stopping continuous dispatch because the polling trigger returned no next execution")
                stopped = true
                return
            }

            try {
                if (lastBatchFull) {
                    inFlightKeys.awaitFreeSlots(refillThreshold, timeout)
                } else {
                    if (inFlightKeys.awaitIdle(timeout)) {
                        // Every key finished before the timeout; the scheduler starts the next run.
                        stopped = true
                    }
                }
            } catch (ex: InterruptedException) {
                Thread.currentThread().interrupt()
                log.debug("Interrupted while waiting for record keys to finish; stopping dispatch")
                stopped = true
            }
        }
    }

    internal fun waitTimeout(): Duration? {
        val now = clock.instant()
        val next = trigger.nextExecution(SimpleTriggerContext(now, now, now)) ?: return null
        val timeout = Duration.between(now, next)
        return if (timeout < MIN_WAIT_TIMEOUT) MIN_WAIT_TIMEOUT else timeout
    }
}
