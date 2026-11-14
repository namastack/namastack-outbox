package io.namastack.outbox.dispatch

import io.namastack.outbox.OutboxProcessingScheduler
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
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Dispatches record keys continuously within one run.
 *
 * At most [OutboxRuntimeSettings.Polling.batchSize] record keys are in flight. A key is in flight
 * from its reservation until its task finishes, including an acknowledged completion commit. The
 * dispatcher selects new keys for free slots after a refill threshold of keys has finished, or after
 * the wait timeout derived from the polling trigger, whichever comes first. Keys already in flight
 * are skipped, so a key is never processed twice at the same time on this instance.
 *
 * A key whose processing ends with an unexpected exception is not selected again until one wait
 * timeout has passed, so a persistent failure does not cause repeated key queries.
 *
 * A run ends when nothing is in flight and a key query finds no keys. A failed key query or record
 * load, a rejected submission, an interrupt, shutdown, or a compatibility cooldown stops further
 * dispatch. Every accepted key task finishes before [dispatch] returns.
 *
 * This type is part of the processing runtime. It is not intended to be used by applications.
 *
 * @param recordRepository Repository for loading record keys and records
 * @param keyProcessor Processor for the records of one key
 * @param taskExecutor Executor for parallel key processing
 * @param settings Effective runtime settings
 * @param trigger Polling trigger that receives poll results and provides the wait timeout
 * @param clock Clock for wait-timeout and hold-back calculations
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
) : KeyDispatcher {
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
     * their reservation and signal the dispatching thread.
     */
    private inner class Run(
        private val partitions: Set<Int>,
        private val isStopping: () -> Boolean,
    ) {
        private val lock = ReentrantLock()
        private val keyFinished = lock.newCondition()

        /** Keys reserved or processed by an accepted task. Guarded by [lock]. */
        private val inFlight = HashSet<String>()

        /**
         * Keys whose task ended with an unexpected error, mapped to when they may be selected again.
         * Guarded by [lock].
         */
        private val heldBack = HashMap<String, Instant>()

        /** Number of keys finished since the last key query. Guarded by [lock]. */
        private var finishedSinceFetch = 0

        /** Completions of accepted tasks; touched only by the dispatching thread. */
        private val completions = ArrayList<CompletableFuture<*>>()

        private var stopped = false

        fun execute() {
            try {
                while (shouldKeepDispatching()) {
                    val keys = selectKeys() ?: break
                    submit(keys)
                    if (keys.isEmpty() && inFlightCount() == 0) break
                    if (!shouldKeepDispatching()) break
                    awaitRefill()
                }
            } finally {
                // Failed fetches, rejected submissions and interrupts must not release active assignments.
                CompletableFuture.allOf(*completions.toTypedArray()).join()
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
         * Queries record keys and reserves at most one key per free slot.
         *
         * @return reserved keys, or `null` if the query failed
         */
        private fun selectKeys(): List<String>? {
            val full =
                lock.withLock {
                    finishedSinceFetch = 0
                    inFlight.size >= batchSize
                }
            if (full) return emptyList()

            val candidates =
                try {
                    recordRepository.findRecordKeysInPartitions(
                        partitions = partitions,
                        batchSize = batchSize,
                        ignoreRecordKeysWithPreviousFailure = settings.processing.stopOnFirstFailure,
                    )
                } catch (ex: Exception) {
                    log.error("Error loading record keys; stopping dispatch for this run", ex)
                    stopped = true
                    trigger.onPollResult(found = 0, requested = batchSize - inFlightCount())
                    return null
                }

            val now = clock.instant()
            val (keys, freeSlots) =
                lock.withLock {
                    heldBack.values.removeIf { !it.isAfter(now) }
                    val freeSlots = batchSize - inFlight.size
                    val keys =
                        candidates
                            .asSequence()
                            .distinct()
                            .filterNot { it in inFlight || it in heldBack }
                            .take(freeSlots)
                            .toList()
                    inFlight.addAll(keys)
                    keys to freeSlots
                }

            trigger.onPollResult(found = keys.size, requested = freeSlots)

            if (keys.isNotEmpty()) {
                log.debug("Selected {} record keys for {} free slots", keys.size, freeSlots)
            }
            return keys
        }

        /**
         * Loads records of reserved keys and submits one task per key. On failure, releases the
         * reservations that have no accepted task and stops dispatching.
         */
        private fun submit(keys: List<String>) {
            var accepted = 0
            try {
                keys.chunked(MAX_RECORD_KEYS_PER_FETCH).forEach { chunk ->
                    val pages = recordRepository.findIncompleteRecordPages(chunk.toSet())
                    chunk.forEach { key ->
                        val page = pages.getValue(key)
                        val completion =
                            asyncTaskExecutor
                                .submitCompletable { processKey(key) { keyProcessor.processRecordKey(key, page) } }
                                .exceptionally { failure ->
                                    val cause = (failure as? CompletionException)?.cause ?: failure
                                    log.error("Error processing key {}", key, cause)
                                    null
                                }
                        completions.add(completion)
                        accepted++
                    }
                }
            } catch (ex: Exception) {
                log.error("Error dispatching record keys; stopping dispatch for this run", ex)
                stopped = true
                val unaccepted = keys.drop(accepted)
                lock.withLock { inFlight.removeAll(unaccepted.toSet()) }
            }
        }

        private fun processKey(
            key: String,
            process: () -> Boolean,
        ) {
            var succeeded = false
            try {
                succeeded = process()
            } finally {
                release(key, succeeded)
            }
        }

        private fun release(
            key: String,
            succeeded: Boolean,
        ) {
            // A key that failed unexpectedly is not selected again before one wait timeout has passed,
            // so a persistent failure cannot turn the refill loop into a busy loop.
            val heldBackUntil =
                if (succeeded) {
                    null
                } else {
                    clock.instant().plus(
                        runCatching { waitTimeout() }.getOrNull() ?: MIN_WAIT_TIMEOUT,
                    )
                }
            lock.withLock {
                if (heldBackUntil != null) heldBack[key] = heldBackUntil
                inFlight.remove(key)
                finishedSinceFetch++
                keyFinished.signalAll()
            }
        }

        private fun inFlightCount(): Int = lock.withLock { inFlight.size }

        /**
         * Waits until the refill threshold of keys has finished since the last query, nothing is in
         * flight, the wait timeout passes, or dispatching must stop.
         */
        private fun awaitRefill() {
            val timeout = waitTimeout()
            if (timeout == null) {
                log.debug("Stopping continuous dispatch because the polling trigger returned no next execution")
                stopped = true
                return
            }

            lock.withLock {
                var remainingNanos = timeout.toNanos()
                try {
                    while (remainingNanos > 0 &&
                        finishedSinceFetch < refillThreshold &&
                        inFlight.isNotEmpty() &&
                        !isStopping() &&
                        !keyProcessor.isCompatibilityCooldownActive()
                    ) {
                        remainingNanos = keyFinished.awaitNanos(remainingNanos)
                    }
                } catch (ex: InterruptedException) {
                    Thread.currentThread().interrupt()
                    log.debug("Interrupted while waiting for record keys to finish; stopping dispatch")
                    stopped = true
                }
            }
        }
    }

    /**
     * Asks the trigger for the delay of a run that completes now.
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
