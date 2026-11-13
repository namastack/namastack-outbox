package io.namastack.outbox.completion

import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Collects queued completions into batches and publishes their write results.
 *
 * One worker per runtime collects and writes batches sequentially. Processing tasks can submit
 * further completions while the current batch is being written.
 *
 * @param queue Supplies pending completions
 * @param writer Writes update and delete batches
 * @param maxBatchSize Maximum completions collected per batch
 * @param maxWait Maximum time to wait for a partially filled batch
 * @author Roland Beisel
 * @since 2.0.0
 */
internal class OutboxCompletionWorker(
    private val queue: OutboxCompletionQueue,
    private val writer: OutboxCompletionWriter,
    private val maxBatchSize: Int,
    private val maxWait: Duration,
) {
    /**
     * Takes submissions from the shared queue and writes them in batches.
     *
     * A batch starts with the first available submission. The worker then collects more until
     * [maxBatchSize] is reached or [maxWait] has elapsed. When the queue closes, it drains queued
     * submissions in batches up to [maxBatchSize] without waiting to fill a partial batch.
     * Each caller receives the result of its own write through the submission's future.
     */
    fun run() {
        while (queue.isAccepting() || queue.hasPending()) {
            val batch = collectBatch() ?: continue
            write(batch)
        }
    }

    private fun collectBatch(): List<OutboxCompletionSubmission>? {
        val first = queue.poll(POLL_NANOS) ?: return null
        val batch = ArrayList<OutboxCompletionSubmission>(maxBatchSize)
        batch.add(first)

        val deadline = System.nanoTime() + maxWait.toNanos()

        while (batch.size < maxBatchSize && queue.isAccepting()) {
            val remainingWaitNanos = deadline - System.nanoTime()
            if (remainingWaitNanos <= 0) break

            val next = queue.poll(minOf(remainingWaitNanos, POLL_NANOS)) ?: continue
            batch.add(next)
        }

        // Shutdown stops waiting for new work, but queued completions still share a batch.
        while (batch.size < maxBatchSize && !queue.isAccepting()) {
            val next = queue.poll(0) ?: break
            batch.add(next)
        }

        return batch
    }

    private fun write(batch: List<OutboxCompletionSubmission>) {
        try {
            val (updateSubmissions, deletionSubmissions) =
                batch.partition { it.completion is OutboxCompletion.Update }

            writeUpdates(updateSubmissions)
            writeDeletions(deletionSubmissions)
        } catch (failure: Throwable) {
            batch.forEach { it.resultFuture.completeExceptionally(failure) }
        }
    }

    private fun writeUpdates(submissions: List<OutboxCompletionSubmission>) {
        if (submissions.isEmpty()) return

        val updates = submissions.map { it.completion as OutboxCompletion.Update }
        publishResults(submissions, writer.writeUpdates(updates))
    }

    private fun writeDeletions(submissions: List<OutboxCompletionSubmission>) {
        if (submissions.isEmpty()) return

        val deletions = submissions.map { it.completion as OutboxCompletion.Delete }
        publishResults(submissions, writer.writeDeletions(deletions))
    }

    private fun publishResults(
        submissions: List<OutboxCompletionSubmission>,
        results: List<Result<Unit>>,
    ) {
        check(submissions.size == results.size) { "Completion writer returned the wrong number of results" }

        submissions.zip(results).forEach { (submission, result) ->
            result.fold(
                onSuccess = { submission.resultFuture.complete(Unit) },
                onFailure = { submission.resultFuture.completeExceptionally(it) },
            )
        }
    }

    private companion object {
        val POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(25)
    }
}
