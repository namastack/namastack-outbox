package io.namastack.outbox.completion

import io.namastack.outbox.runtime.OutboxRuntimeSettings.CompletionBatching
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.core.task.TaskExecutor
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Coordinates a shared completion queue and its single writer worker.
 *
 * The queue opens before the worker is scheduled, but callers can submit only after scheduling
 * succeeds. Shutdown closes the queue so the worker can drain pending submissions.
 * The executor is borrowed; its owner initializes and shuts it down.
 * [shutdownTimeout] limits how long shutdown waits for the worker; it does not cancel pending writes.
 *
 * @param writer Persists the batches collected by the worker
 * @param executor Initialized asynchronous executor with capacity for one long-running worker
 * @param settings Batch size and maximum batch wait
 * @param shutdownTimeout Maximum time [stop] waits for the worker to exit
 * @author Roland Beisel
 * @since 2.0.0
 */
internal class OutboxCompletionBatcher(
    private val executor: TaskExecutor,
    private val shutdownTimeout: Duration,
    writer: OutboxCompletionWriter,
    settings: CompletionBatching,
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(OutboxCompletionBatcher::class.java)

    private val queue: OutboxCompletionQueue
    private val worker: OutboxCompletionWorker

    private val lifecycleLock = Any()
    private var workerExitLatch = CountDownLatch(0)

    @Volatile
    private var acceptingSubmissions = false

    init {
        require(shutdownTimeout >= Duration.ZERO) { "shutdownTimeout must not be negative" }

        queue = OutboxCompletionQueue()
        worker = OutboxCompletionWorker(queue, writer, settings.maxBatchSize, settings.maxWait)
    }

    /**
     * Schedules the completion worker and then allows callers to submit.
     *
     * The queue is opened first so the worker cannot see it as closed and exit during startup.
     * If the executor throws an exception while scheduling, the queue closes and the exception propagates.
     *
     * @throws IllegalStateException if the worker from a previous run is still active
     * @throws RuntimeException if the executor cannot schedule the worker
     */
    override fun start() {
        synchronized(lifecycleLock) {
            if (acceptingSubmissions) return
            check(workerExitLatch.count == 0L) { "Previous completion writer is still stopping" }

            val exitLatch = CountDownLatch(1)
            workerExitLatch = exitLatch
            // The worker must see an open queue when it starts; submissions remain gated below.
            queue.startAccepting()
            try {
                executor.execute {
                    try {
                        worker.run()
                    } finally {
                        exitLatch.countDown()
                    }
                }
                acceptingSubmissions = true
            } catch (failure: Exception) {
                queue.stopAccepting()
                exitLatch.countDown()
                throw failure
            }
        }
    }

    /**
     * Closes the queue and waits up to [shutdownTimeout] for the worker to exit.
     *
     * The worker continues writing queued submissions after the wait times out. If the waiting
     * thread is interrupted, its interrupted status is restored and the wait ends.
     */
    override fun stop() {
        val exitLatch =
            synchronized(lifecycleLock) {
                acceptingSubmissions = false
                queue.stopAccepting()
                workerExitLatch
            }

        try {
            if (!exitLatch.await(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("Completion writer did not finish within {}", shutdownTimeout)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            log.warn("Interrupted while waiting for the completion writer to stop")
        }
    }

    /**
     * Reports whether the worker was scheduled and new submissions are accepted.
     *
     * @return true after successful scheduling and before shutdown begins
     */
    override fun isRunning(): Boolean = acceptingSubmissions

    /**
     * Starts after partition coordination and before record processing.
     *
     * @return phase 2; Spring stops this batcher after the processing scheduler
     */
    override fun getPhase(): Int = 2

    /**
     * Submits a completion while submissions are accepted.
     *
     * Callers must wait for the returned future before submitting their next processing result.
     * A successful future means the write committed; a failed or uncertain write completes it exceptionally.
     *
     * @param completion Update or deletion to write
     * @return future for this write, or null if the batcher has not started or closes before enqueueing
     */
    fun submit(completion: OutboxCompletion): CompletableFuture<Unit>? =
        if (acceptingSubmissions) queue.submit(completion) else null
}
