package io.namastack.outbox

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/**
 * Limits the number of concurrently processed outbox tasks.
 *
 * This class provides a mechanism to restrict the number of concurrently processed aggregate IDs (or tasks)
 * using a semaphore. It also tracks which IDs are currently being processed and how many have been processed.
 *
 * Thread-safe for concurrent usage.
 *
 * @property limit The maximum number of concurrent tasks allowed.
 */
class OutboxProcessingLimiter(
    private val limit: Int,
) {
    /**
     * Semaphore controlling the number of concurrent tasks.
     */
    private val semaphore: Semaphore = Semaphore(limit)

    /**
     * Set of IDs currently being processed.
     */
    private val processingIds: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    /**
     * Counter for the number of processed tasks.
     */
    private val processedCounter = AtomicInteger(0)

    /**
     * Acquires a permit for the given ID, blocking if the limit is reached.
     * Adds the ID to the set of currently processing IDs.
     *
     * @param id The unique identifier for the task to acquire.
     */
    fun acquire(id: String) {
        semaphore.acquire()
        processingIds.add(id)
    }

    /**
     * Releases a permit for the given ID, removes it from the processing set, and increments the processed counter.
     *
     * @param id The unique identifier for the task to release.
     */
    fun release(id: String) {
        check(processingIds.remove(id)) {
            "Attempted to release ID '$id' which is not currently being processed."
        }
        processedCounter.incrementAndGet()
        semaphore.release()
    }

    /**
     * Blocks until all permits are acquired (i.e., all tasks have completed).
     * Used to wait for all processing to finish.
     */
    fun awaitAll() {
        semaphore.acquire(limit)
        semaphore.release(limit)
    }

    /**
     * Returns a snapshot of the IDs currently being processed.
     */
    fun getUnprocessedIds(): Set<String> = processingIds.toSet()

    /**
     * Returns the total number of tasks that have been processed.
     */
    fun getProcessedCount(): Int = processedCounter.get()
}
