package io.namastack.outbox

import org.springframework.jdbc.core.JdbcOperations
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Collects completion requests and coordinates background writers for one fixed JDBC namespace.
 *
 * Workers submit immutable metadata and wait for the committed result before advancing their
 * record key. [JdbcOutboxCompletionWriter] owns database writes and recovery; this class owns
 * queue admission, batch collection and writer shutdown. Application transactions must use the
 * repository's synchronous path instead.
 *
 * Close after record processing stops to flush admitted requests. The JDBC operations and
 * transaction manager are borrowed and must address the same fixed table namespace. Thread-local
 * tenant routing cannot be transferred to the writer threads.
 *
 * @param jdbc JDBC operations used for completion updates
 * @param transactionTemplate Transaction settings and manager used for completion commits
 * @param tableName Fully resolved record table name, fixed for this batcher's lifetime
 * @param batchSize Maximum completions per transaction
 * @param maxWait Collection window starting with the first completion, excluding database time
 * @param queueCapacity Maximum number of queued completions
 * @param writerCount Maximum parallel completion transactions; each writer borrows one connection
 * @param bulkUpdateFactory Lazily selects an optional dialect-specific multi-row update strategy
 *
 * @author Roland Beisel
 * @since 1.12.0
 */
internal class JdbcOutboxCompletionBatcher(
    jdbc: JdbcOperations,
    transactionTemplate: TransactionTemplate,
    tableName: String,
    private val batchSize: Int,
    maxWait: Duration,
    queueCapacity: Int,
    writerCount: Int = 1,
    bulkUpdateFactory: () -> JdbcOutboxBulkCompletionUpdater? = { null },
) : AutoCloseable {
    companion object {
        private const val QUEUE_POLL_MILLIS = 100L
        private const val SHUTDOWN_TIMEOUT_SECONDS = 30L
    }

    private val writer = JdbcOutboxCompletionWriter(jdbc, transactionTemplate, tableName, bulkUpdateFactory)
    private val maxWaitNanos = maxWait.toNanos()
    private val queue = LinkedBlockingQueue<JdbcOutboxCompletion>(queueCapacity)
    private val admissionLock = Any()
    private val workers: List<Thread>

    @Volatile
    private var accepting = true

    init {
        require(batchSize > 0) { "Completion batch size must be positive" }
        require(!maxWait.isNegative) { "Completion collection window must not be negative" }
        require(writerCount > 0) { "Completion writer count must be positive" }
        workers =
            List(writerCount) { index ->
                Thread({ runWriter() }, "outbox-jdbc-completion-writer-${index + 1}").apply {
                    isDaemon = true
                }
            }
        try {
            workers.forEach { it.start() }
        } catch (failure: Throwable) {
            accepting = false
            throw failure
        }
    }

    /** Enqueues completion metadata and waits until its committed update result is known. */
    fun complete(record: OutboxRecord<*>): Boolean {
        check(record.status == OutboxRecordStatus.COMPLETED) { "Only completed records can be batched" }
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "Completion batching must not detach an application transaction"
        }
        check(!TransactionSynchronizationManager.isSynchronizationActive()) {
            "Completion batching must not detach transaction synchronization"
        }
        val completion = JdbcOutboxCompletion(record)
        synchronized(admissionLock) {
            check(accepting) { "Completion writer is closed" }
            if (!queue.offer(completion)) throw RejectedExecutionException("Completion queue is full")
        }
        return completion.awaitCommit()
    }

    /** Stops admissions and waits for admitted writes without interrupting an in-flight commit. */
    override fun close() {
        synchronized(admissionLock) { accepting = false }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SHUTDOWN_TIMEOUT_SECONDS)
        workers.forEach { worker ->
            val remaining = deadline - System.nanoTime()
            if (remaining > 0) worker.join(TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1))
        }
        check(workers.none { it.isAlive }) {
            "Completion writers did not finish within $SHUTDOWN_TIMEOUT_SECONDS seconds"
        }
    }

    private fun runWriter() {
        var terminalFailure: Throwable = IllegalStateException("Completion writer stopped")
        var activeBatch: List<JdbcOutboxCompletion> = emptyList()
        try {
            while (accepting || queue.isNotEmpty()) {
                val first = queue.poll(QUEUE_POLL_MILLIS, TimeUnit.MILLISECONDS) ?: continue
                val batch = ArrayList<JdbcOutboxCompletion>(batchSize)
                batch.add(first)
                activeBatch = batch
                processBatch(batch)
                activeBatch = emptyList()
            }
        } catch (failure: Throwable) {
            terminalFailure = failure
        } finally {
            activeBatch.forEach { it.fail(terminalFailure) }
            failPendingCompletions(terminalFailure)
        }
    }

    private fun processBatch(batch: MutableList<JdbcOutboxCompletion>) {
        try {
            collectBatch(batch)
            writer.write(batch)
        } catch (failure: Throwable) {
            batch.forEach { it.fail(failure) }
            if (failure is Error) throw failure
        }
    }

    private fun collectBatch(batch: MutableList<JdbcOutboxCompletion>) {
        val deadline = System.nanoTime() + maxWaitNanos
        queue.drainTo(batch, batchSize - 1)
        while (batch.size < batchSize && accepting) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) break
            // Observe shutdown at least every 100 ms without interrupting a JDBC operation.
            val waitNanos = minOf(remaining, TimeUnit.MILLISECONDS.toNanos(QUEUE_POLL_MILLIS))
            val next = queue.poll(waitNanos, TimeUnit.NANOSECONDS)
            if (next != null) batch.add(next)
        }
    }

    private fun failPendingCompletions(failure: Throwable) {
        synchronized(admissionLock) {
            accepting = false
            while (true) {
                val completion = queue.poll() ?: break
                completion.fail(failure)
            }
        }
    }
}
