package io.namastack.outbox.completion

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bounds pending completions and coordinates submissions with shutdown.
 *
 * A slot becomes available when a worker removes a completion from the queue. The fair semaphore
 * lets waiting callers take available slots in arrival order.
 *
 * @param capacity Maximum number of completions waiting in the queue
 * @author Roland Beisel
 * @since 1.12.0
 */
internal class JdbcOutboxCompletionQueue(
    capacity: Int,
) {
    private val submissionLock = Any()
    private val accepting = AtomicBoolean(false)
    private val queue = ArrayBlockingQueue<JdbcOutboxCompletionSubmission>(capacity)
    private val slots = Semaphore(capacity, true)

    /** Opens the queue to submissions. */
    fun startAccepting() {
        synchronized(submissionLock) { accepting.set(true) }
    }

    /**
     * Closes the queue to new submissions without discarding queued work.
     *
     * @return true if the queue was accepting submissions
     */
    fun stopAccepting(): Boolean = synchronized(submissionLock) { accepting.getAndSet(false) }

    /**
     * Reports whether submissions are currently accepted.
     *
     * @return true while the queue is open to submissions
     */
    fun isAccepting(): Boolean = accepting.get()

    /**
     * Reports whether any completion remains queued.
     *
     * @return true if at least one completion is waiting for a worker
     */
    fun hasPending(): Boolean = queue.isNotEmpty()

    /**
     * Enqueues a completion, waiting for capacity while submissions are accepted.
     *
     * The caller's interrupted status is restored after waiting. Closing the queue wakes a waiting
     * caller within one poll interval; that caller then receives null.
     *
     * @param completion Immutable completion action
     * @return Future completed by a worker, or null if the queue closes before submission
     */
    fun submit(completion: JdbcOutboxCompletion): CompletableFuture<Unit>? {
        var interrupted = false
        try {
            while (true) {
                if (!accepting.get()) return null
                try {
                    if (slots.tryAcquire(POLL_MILLIS, TimeUnit.MILLISECONDS)) break
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }

        val submission = JdbcOutboxCompletionSubmission(completion)

        synchronized(submissionLock) {
            if (!accepting.get()) {
                slots.release()
                return null
            }
            check(queue.offer(submission)) { "Completion queue capacity is inconsistent" }
        }

        return submission.resultFuture
    }

    /**
     * Takes a queued submission and releases its queue slot.
     *
     * @param waitNanos Maximum time to wait for a completion
     * @return Next submission, or null when the wait expires or the worker is interrupted
     */
    fun poll(waitNanos: Long): JdbcOutboxCompletionSubmission? {
        val submission =
            try {
                queue.poll(waitNanos, TimeUnit.NANOSECONDS)
            } catch (_: InterruptedException) {
                null
            }

        if (submission != null) slots.release()

        return submission
    }

    private companion object {
        const val POLL_MILLIS = 25L
    }
}
