package io.namastack.outbox

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/**
 * Snapshot of a record's completion metadata and the result of its database commit.
 *
 * Copies only processing metadata, so waiting writes retain neither the mutable record nor its
 * payload and context. The submitting worker waits for [awaitCommit] before advancing its key.
 *
 * @param record Completed record whose processing metadata is copied
 *
 * @author Roland Beisel
 * @since 1.12.0
 */
internal class JdbcOutboxCompletion(
    record: OutboxRecord<*>,
) {
    val id = record.id
    val completedAt = record.completedAt
    val failureCount = record.failureCount
    val failureReason = record.failureReason
    val nextRetryAt = record.nextRetryAt

    private val committed = CompletableFuture<Boolean>()

    /** Publishes the exact update result after the transaction has committed. */
    fun confirmCommit(updated: Boolean) {
        committed.complete(updated)
    }

    /** Releases the waiting worker with the write failure, without acknowledging completion. */
    fun fail(failure: Throwable) {
        committed.completeExceptionally(failure)
    }

    /**
     * Waits for the committed result and propagates the original write failure.
     *
     * Waiting is deliberately uninterruptible: the worker must retain its partition assignment
     * until the database operation finishes. The caller's interrupted status is preserved.
     */
    fun awaitCommit(): Boolean =
        try {
            committed.join()
        } catch (exception: CompletionException) {
            when (val cause = exception.cause) {
                is RuntimeException -> throw cause
                is Error -> throw cause
                else -> throw IllegalStateException("Completion transaction failed", cause)
            }
        }
}
