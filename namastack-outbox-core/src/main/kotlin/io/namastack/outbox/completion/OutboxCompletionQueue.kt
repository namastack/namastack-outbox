package io.namastack.outbox.completion

import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Queues processing results and coordinates submissions with shutdown.
 *
 * Processing tasks wait for each submitted write to commit before submitting another. The number
 * of outstanding writes is therefore bounded by active processing tasks, without a separate queue limit.
 *
 * @author Roland Beisel
 * @since 2.0.0
 */
internal class OutboxCompletionQueue {
    // Queue insertion can wait for its internal locks. Avoid pinning a Java 21 virtual thread.
    private val submissionLock = ReentrantLock()
    private val accepting = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<OutboxCompletionSubmission>()

    /** Opens the queue to submissions. */
    fun startAccepting() {
        submissionLock.withLock { accepting.set(true) }
    }

    /**
     * Stops accepting new submissions. Queued completions remain available to the worker.
     *
     * This method does not drain the queue or wait for writes to finish.
     */
    fun stopAccepting() {
        submissionLock.withLock { accepting.set(false) }
    }

    /**
     * Reports whether submissions are currently accepted.
     *
     * @return true while the queue is open to submissions
     */
    fun isAccepting(): Boolean = accepting.get()

    /**
     * Reports whether any completion remains queued.
     *
     * @return true if at least one completion is waiting for the worker
     */
    fun hasPending(): Boolean = queue.isNotEmpty()

    /**
     * Enqueues a completion while submissions are accepted.
     *
     * Insertion and shutdown share a lock so accepted submissions remain available to the draining worker.
     *
     * @param completion Immutable completion action
     * @return Future completed by the worker, or null if the queue closes before submission
     */
    fun submit(completion: OutboxCompletion): CompletableFuture<Unit>? {
        submissionLock.withLock {
            if (!accepting.get()) return null
            val submission = OutboxCompletionSubmission(completion)
            queue.add(submission)

            return submission.resultFuture
        }
    }

    /**
     * Takes a queued submission.
     *
     * @param waitNanos Maximum time to wait for a completion
     * @return Next submission, or null when the wait expires or the worker is interrupted
     */
    fun poll(waitNanos: Long): OutboxCompletionSubmission? =
        try {
            queue.poll(waitNanos, TimeUnit.NANOSECONDS)
        } catch (_: InterruptedException) {
            null
        }
}
