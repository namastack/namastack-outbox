package io.namastack.outbox.dispatch

import io.namastack.outbox.OutboxRecordPage
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.OutboxRecordRepository.Companion.MAX_RECORD_KEYS_PER_FETCH
import io.namastack.outbox.runtime.OutboxRuntimeSettings
import io.namastack.outbox.trigger.OutboxPollingTrigger
import org.slf4j.LoggerFactory
import org.springframework.core.task.AsyncTaskExecutor
import org.springframework.scheduling.support.SimpleTriggerContext
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CompletionException

/**
 * Dispatches record keys continuously within one run.
 *
 * At most [OutboxRuntimeSettings.Polling.batchSize] record keys are in flight. A key is in flight
 * from its submission until its task finishes, including an acknowledged completion commit. Keys in
 * flight are never fetched again, so a key is never processed twice at the same time on this instance.
 *
 * A fetch queries record keys, drops the keys in flight, keeps at most one key per free slot, and
 * reports the kept keys against the free slots to the polling trigger. The wait timeout is the delay
 * the polling trigger returns for a run that completes now. What follows depends on the fetched batch:
 *
 * - **Empty batch**: with nothing in flight, the run ends. The scheduler starts the next run when the
 *   trigger fires.
 * - **Partial batch** (fewer keys than free slots, including an empty batch while keys are in flight):
 *   the dispatcher waits until every key in flight has finished, which ends the run. With every key
 *   finished, the run behaves like a batch dispatch run. If the wait timeout passes first, a slow key
 *   is in flight and slots are free, so the dispatcher fetches again.
 * - **Full batch** (a key for every free slot): the dispatcher waits until a quarter of the batch size
 *   has finished, then fetches again to refill the free slots. If the wait timeout passes first, the
 *   dispatcher fetches again as after a partial batch. With no free slot, the fetch skips the query and
 *   counts as a full batch.
 *
 * A run also ends after a failed key query or record load, a rejected submission, a key whose
 * processing ends with an exception, an interrupt, shutdown, a due partition rebalance, a compatibility
 * cooldown, or a polling trigger that returns no next execution. Every accepted key task finishes before [dispatch] returns.
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
internal class ContinuousKeyDispatcher(
    private val recordRepository: OutboxRecordRepository,
    private val keyProcessor: OutboxRecordKeyProcessor,
    private val taskExecutor: AsyncTaskExecutor,
    private val settings: OutboxRuntimeSettings,
    private val trigger: OutboxPollingTrigger,
    private val clock: Clock,
) : OutboxKeyDispatcher {
    private companion object {
        private val MIN_WAIT_TIMEOUT: Duration = Duration.ofMillis(1)
    }

    private val log = LoggerFactory.getLogger(ContinuousKeyDispatcher::class.java)

    private val batchSize: Int = settings.polling.batchSize

    private val refillThreshold: Int = maxOf(1, batchSize / 4)

    override fun dispatch(
        partitions: Set<Int>,
        shouldStop: () -> Boolean,
    ) = Run(partitions, shouldStop).execute()

    override fun isCompatibilityCooldownActive(): Boolean = keyProcessor.isCompatibilityCooldownActive()

    /**
     * State of one dispatch run. Only the dispatching thread fetches and submits; key tasks release
     * their slot in [InFlightKeys] when they finish.
     */
    private inner class Run(
        private val partitions: Set<Int>,
        private val shouldStop: () -> Boolean,
    ) {
        private val inFlightKeys = InFlightKeys(batchSize)

        /** Whether the last fetched batch filled every free slot. Touched only by the dispatching thread. */
        private var lastBatchFull = false

        /** Written by key tasks and the dispatching thread. */
        @Volatile
        private var stopped = false

        /**
         * Fetches, submits, and waits until dispatching stops, then waits for every accepted key task.
         */
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
            if (shouldStop()) {
                log.debug("Stopping continuous dispatch because a rebalance is due or the scheduler is shutting down")
                return false
            }
            if (keyProcessor.isCompatibilityCooldownActive()) {
                log.debug("Stopping continuous dispatch because a compatibility cooldown is active")
                return false
            }
            return true
        }

        /**
         * Queries record keys, drops the keys in flight, keeps at most one key per free slot, and reports
         * the result to the polling trigger. Skips the query when no slot is free.
         *
         * @return keys to submit; empty if no key was found, no slot is free, or the query failed
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

        /**
         * Queries up to one batch of record keys in the run's partitions.
         *
         * @return record keys in processing order, or an empty list if the query failed, which stops dispatching
         */
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
         * Loads the records of the given keys and submits one task per key. A failed record load or a
         * rejected submission stops dispatching; tasks submitted before the failure keep running.
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

        /**
         * Reserves a slot for [key] and submits its task. Releases the slot if the executor rejects the task.
         */
        private fun submitKey(
            key: String,
            page: OutboxRecordPage,
        ) {
            check(inFlightKeys.tryReserve(key)) { "No free slot for record key '$key'" }
            try {
                taskExecutor
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

        /**
         * Processes the records of [key] and releases its slot. Processing that ends with an exception
         * stops dispatching.
         */
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
         * Waits until the next fetch is due or the run ends.
         *
         * After a full batch, waits until the refill threshold of slots is free or the wait timeout
         * passes. After a partial batch, waits until every key in flight has finished, which ends the
         * run, or the wait timeout passes. A polling trigger that returns no next execution, or an
         * interrupt, stops dispatching.
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

    /**
     * Asks the polling trigger for the delay of a run that completes now.
     *
     * @return wait timeout of at least 1 ms, or `null` when the trigger returns no next execution
     */
    internal fun waitTimeout(): Duration? {
        val now = clock.instant()
        val next = trigger.nextExecution(SimpleTriggerContext(now, now, now)) ?: return null
        val timeout = Duration.between(now, next)
        return if (timeout < MIN_WAIT_TIMEOUT) MIN_WAIT_TIMEOUT else timeout
    }
}
