package io.namastack.outbox.completion

import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Collects queued completions into batches and publishes their write results.
 *
 * Each executor thread collects its own batches from the shared queue. The writer supports
 * concurrent calls.
 *
 * @param queue Supplies pending completions
 * @param writer Writes update and delete batches
 * @param maxBatchSize Maximum completions collected per batch
 * @param maxWait Maximum time to wait for a partially filled batch
 * @author Roland Beisel
 * @since 1.12.0
 */
internal class JdbcOutboxCompletionWorker(
    private val queue: JdbcOutboxCompletionQueue,
    private val writer: JdbcOutboxCompletionWriter,
    private val maxBatchSize: Int,
    private val maxWait: Duration,
) {
    /**
     * Takes submissions from the shared queue and writes them in batches.
     *
     * A batch starts with the first available submission. The worker then collects more until
     * [maxBatchSize] is reached, [maxWait] has elapsed, or the queue closes. After the queue
     * closes, it writes any submissions still waiting before returning. Each caller receives
     * the result of its own write through the submission's future.
     */
    fun run() {
        while (queue.isAccepting() || queue.hasPending()) {
            val first = queue.poll(POLL_NANOS) ?: continue
            val batch = ArrayList<JdbcOutboxCompletionSubmission>(maxBatchSize)
            batch.add(first)

            val deadline = System.nanoTime() + maxWait.toNanos()

            while (batch.size < maxBatchSize && queue.isAccepting()) {
                val remainingWaitNanos = deadline - System.nanoTime()
                if (remainingWaitNanos <= 0) break

                val next = queue.poll(minOf(remainingWaitNanos, POLL_NANOS)) ?: continue
                batch.add(next)
            }

            write(batch)
        }
    }

    private fun write(batch: List<JdbcOutboxCompletionSubmission>) {
        try {
            val (updateSubmissions, deletionSubmissions) =
                batch.partition { it.completion is JdbcOutboxCompletion.Update }

            writeUpdates(updateSubmissions)
            writeDeletions(deletionSubmissions)
        } catch (failure: Throwable) {
            batch.forEach { it.resultFuture.completeExceptionally(failure) }
        }
    }

    private fun writeUpdates(submissions: List<JdbcOutboxCompletionSubmission>) {
        if (submissions.isEmpty()) return

        val updates = submissions.map { it.completion as JdbcOutboxCompletion.Update }
        publishResults(submissions, writer.writeUpdates(updates))
    }

    private fun writeDeletions(submissions: List<JdbcOutboxCompletionSubmission>) {
        if (submissions.isEmpty()) return

        val deletions = submissions.map { it.completion as JdbcOutboxCompletion.Delete }
        publishResults(submissions, writer.writeDeletions(deletions))
    }

    private fun publishResults(
        submissions: List<JdbcOutboxCompletionSubmission>,
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
