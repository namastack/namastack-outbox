package io.namastack.outbox.completion

import io.namastack.outbox.config.JdbcOutboxConfigurationProperties.CompletionBatching
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Coordinates a shared completion queue and its writer workers.
 *
 * The queue opens before workers are scheduled, but callers can submit only after every worker
 * has been scheduled. Shutdown closes the queue so workers can drain pending submissions.
 * [shutdownTimeout] limits how long shutdown waits for workers; it does not cancel their writes.
 *
 * @param writer Persists the batches collected by workers
 * @param executor Schedules the configured number of workers
 * @param settings Number of workers, batch size, and maximum batch wait
 * @param shutdownTimeout Maximum time [stop] waits for workers to exit
 * @author Roland Beisel
 * @since 1.12.0
 */
internal class JdbcOutboxCompletionBatcher(
    private val executor: ThreadPoolTaskExecutor,
    private val shutdownTimeout: Duration,
    writer: JdbcOutboxCompletionWriter,
    settings: CompletionBatching,
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(JdbcOutboxCompletionBatcher::class.java)
    private val writerCount = settings.writerCount

    private val queue: JdbcOutboxCompletionQueue
    private val worker: JdbcOutboxCompletionWorker

    private val lifecycleLock = Any()
    private var workerExitLatch = CountDownLatch(0)

    @Volatile
    private var acceptingSubmissions = false

    init {
        require(writerCount > 0) { "writerCount must be positive" }
        require(settings.maxBatchSize > 0) { "maxBatchSize must be positive" }
        require(settings.maxWait > Duration.ZERO && settings.maxWait.toNanos() > 0) { "maxWait must be positive" }
        require(shutdownTimeout >= Duration.ZERO) { "shutdownTimeout must not be negative" }

        queue = JdbcOutboxCompletionQueue(Math.multiplyExact(writerCount, settings.maxBatchSize))
        worker = JdbcOutboxCompletionWorker(queue, writer, settings.maxBatchSize, settings.maxWait)
    }

    /**
     * Schedules all completion workers and then allows callers to submit.
     *
     * The queue is opened first so a worker cannot see it as closed and exit during startup.
     * If scheduling fails, the queue is closed and the failure is propagated.
     *
     * @throws IllegalStateException if workers from a previous run are still active
     */
    override fun start() {
        synchronized(lifecycleLock) {
            if (acceptingSubmissions) return
            check(workerExitLatch.count == 0L) { "Previous completion writers are still stopping" }

            val exitLatch = CountDownLatch(writerCount)
            workerExitLatch = exitLatch
            // Workers must see an open queue when they start; submissions remain gated below.
            queue.startAccepting()
            try {
                scheduleWorkers(exitLatch)
                acceptingSubmissions = true
            } catch (failure: Exception) {
                queue.stopAccepting()
                throw failure
            }
        }
    }

    /**
     * Closes the queue and waits up to [shutdownTimeout] for workers to exit.
     *
     * Workers continue writing queued submissions after the wait times out. If the waiting
     * thread is interrupted, its interrupted status is restored and the wait ends.
     */
    override fun stop() {
        val exitLatch =
            synchronized(lifecycleLock) {
                if (!acceptingSubmissions) return
                acceptingSubmissions = false
                queue.stopAccepting()
                workerExitLatch
            }

        try {
            if (!exitLatch.await(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("Completion writers did not finish within {}", shutdownTimeout)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            log.warn("Interrupted while waiting for completion writers to stop")
        }
    }

    /**
     * Reports whether all workers were scheduled and new submissions are accepted.
     *
     * @return true after successful startup and before shutdown begins
     */
    override fun isRunning(): Boolean = acceptingSubmissions

    /**
     * Starts after partition coordination and before record processing.
     *
     * @return phase 2; Spring stops this batcher after the processing scheduler
     */
    override fun getPhase(): Int = 2

    /**
     * Submits a completion, waiting for queue capacity while submissions are accepted.
     *
     * An interrupted wait restores the caller's interrupted status. A successful future means
     * the write committed; a failed or uncertain write completes the future exceptionally.
     *
     * @param completion Update or deletion to write
     * @return future for this write, or null if the batcher has not started or closes before enqueueing
     */
    fun submit(completion: JdbcOutboxCompletion): CompletableFuture<Unit>? =
        if (acceptingSubmissions) queue.submit(completion) else null

    /**
     * Schedules workers and counts down the latch for any that could not be scheduled.
     *
     * Scheduled workers count down when they exit. The caller closes the queue if scheduling
     * fails, allowing those workers to exit as well.
     *
     * @param exitLatch Tracks worker exits, including slots for workers that could not be scheduled
     * @throws Exception if the executor fails to schedule a worker
     */
    private fun scheduleWorkers(exitLatch: CountDownLatch) {
        var scheduled = 0
        try {
            repeat(writerCount) {
                executor.execute {
                    try {
                        worker.run()
                    } finally {
                        exitLatch.countDown()
                    }
                }
                scheduled++
            }
        } catch (failure: Exception) {
            // Unscheduled workers cannot count down when they exit.
            repeat(writerCount - scheduled) { exitLatch.countDown() }
            throw failure
        }
    }
}
