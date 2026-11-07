package io.namastack.outbox.completion

import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/**
 * Persists completions through the batcher outside caller transactions.
 *
 * An active caller transaction is rejected because a writer thread cannot participate in it.
 * The processing thread waits for a batched write to commit before returning to its record key.
 * During shutdown, new completions fall back to the repository after the batcher closes.
 *
 * @param repository Repository used for shutdown fallback writes
 * @param batcher Collects completions for the writer threads
 * @author Roland Beisel
 * @since 1.12.0
 */
internal class JdbcOutboxCompletionBatchingPersistence(
    private val repository: OutboxRecordRepository,
    private val batcher: JdbcOutboxCompletionBatcher,
) : OutboxCompletionPersistence {
    override fun updateCompleted(record: OutboxRecord<*>) {
        requireNoCallerTransaction()

        val pendingCommit = batcher.submit(JdbcOutboxCompletion.Update.from(record))
        if (pendingCommit == null) {
            // A handler may finish after shutdown stops the batcher; persist its completion directly.
            repository.updateProcessingState(record)
            return
        }

        pendingCommit.awaitCommit()
    }

    override fun deleteCompleted(id: String) {
        requireNoCallerTransaction()

        val pendingCommit = batcher.submit(JdbcOutboxCompletion.Delete(id))
        if (pendingCommit == null) {
            // A handler may finish after shutdown stops the batcher; persist its completion directly.
            repository.deleteById(id)
            return
        }

        pendingCommit.awaitCommit()
    }

    private fun requireNoCallerTransaction() {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "JDBC completion batching cannot complete records inside an active transaction"
        }
    }

    private fun CompletableFuture<Unit>.awaitCommit() {
        try {
            join()
        } catch (failure: CompletionException) {
            when (val cause = failure.cause) {
                is RuntimeException -> throw cause
                is Error -> throw cause
                else -> throw IllegalStateException("Completion write failed", cause)
            }
        }
    }
}
