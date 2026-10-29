package io.namastack.outbox

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PostgreSQL completion batching across independently processed record keys.
 *
 * Each caller waits for its write transaction to commit before returning. The scheduler therefore
 * cannot advance a key or release its assignment while that key has an outstanding completion.
 * This resource must be closed after its processing runtimes stop. It borrows the JDBC client and
 * transaction template and never closes their datasource. Application transactions are handled
 * by the repository's normal completion path rather than this independent writer.
 *
 * @since 1.12.0
 */
class PostgresOutboxCompletionBatcher internal constructor(
    private val jdbc: JdbcClient,
    private val transactions: TransactionTemplate,
    internal val tableName: String,
    private val batchSize: Int,
    maxWait: Duration,
) : AutoCloseable {
    private data class Request(
        val id: String,
        val status: String,
        val completed: Instant?,
        val failures: Int,
        val reason: String?,
        val retry: Instant,
        val ack: CompletableFuture<Boolean> = CompletableFuture(),
    )

    private val waitNanos = maxWait.toNanos()
    private val queue = LinkedBlockingQueue<Request>(4096)
    private val accepting = AtomicBoolean(true)
    private val admission = Any()
    private val worker: Thread

    init {
        require(batchSize in 1..512 && waitNanos >= 0)
        worker =
            Thread({ run() }, "outbox-completion-batcher").apply {
                isDaemon = true
                start()
            }
    }

    internal fun complete(record: OutboxRecord<*>): Boolean {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "Completion batching must not detach a write from an application transaction"
        }
        val request =
            Request(
                record.id,
                record.status.name,
                record.completedAt,
                record.failureCount,
                record.failureReason,
                record.nextRetryAt,
            )
        synchronized(admission) {
            check(accepting.get()) { "Completion batcher is closed" }
            if (!queue.offer(request)) throw RejectedExecutionException("Completion batch queue is full")
        }
        try {
            // An interrupt cannot release assignments while a submitted completion is still in flight.
            return request.ack.join()
        } catch (ex: CompletionException) {
            throw (ex.cause as? RuntimeException ?: IllegalStateException("Completion batch failed", ex.cause))
        }
    }

    private fun run() {
        try {
            while (accepting.get() || queue.isNotEmpty()) {
                val first =
                    try {
                        queue.poll(100, TimeUnit.MILLISECONDS)
                    } catch (
                        _: InterruptedException,
                    ) {
                        null
                    } ?: continue
                val batch = ArrayList<Request>(batchSize)
                batch.add(first)
                queue.drainTo(batch, batchSize - 1)
                val deadline = System.nanoTime() + waitNanos
                while (batch.size < batchSize && accepting.get()) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) break
                    val next =
                        try {
                            queue.poll(remaining, TimeUnit.NANOSECONDS)
                        } catch (
                            _: InterruptedException,
                        ) {
                            null
                        } ?: break
                    batch.add(next)
                }
                try {
                    val committed = transactions.execute { writeBatch(batch) }
                    batch.forEach { it.ack.complete(it.id in committed) }
                } catch (ex: DataIntegrityViolationException) {
                    // One poisoned record must not repeatedly roll back unrelated keys.
                    // The original batch has rolled back; retry each write in its own transaction.
                    batch.forEach { request ->
                        try {
                            val committed = transactions.execute { writeBatch(listOf(request)) }
                            request.ack.complete(request.id in committed)
                        } catch (failure: Throwable) {
                            request.ack.completeExceptionally(failure)
                            if (failure is Error) {
                                batch.forEach { if (!it.ack.isDone) it.ack.completeExceptionally(failure) }
                                throw failure
                            }
                        }
                    }
                } catch (ex: Throwable) {
                    batch.forEach { it.ack.completeExceptionally(ex) }
                    if (ex is Error) throw ex
                }
            }
        } finally {
            val abandoned = mutableListOf<Request>()
            synchronized(admission) {
                accepting.set(false)
                queue.drainTo(abandoned)
            }
            abandoned.forEach { it.ack.completeExceptionally(IllegalStateException("Completion writer stopped")) }
        }
    }

    private fun writeBatch(batch: List<Request>): Set<String> {
        val values =
            batch.indices.joinToString(",") { i ->
                "(CAST(:id$i AS varchar),CAST(:status$i AS varchar),CAST(:completed$i AS timestamptz)," +
                    "CAST(:failures$i AS integer),CAST(:reason$i AS varchar),CAST(:retry$i AS timestamptz))"
            }
        val query =
            jdbc.sql(
                """
            UPDATE $tableName r SET status=v.status, completed_at=v.completed, failure_count=v.failures,
                failure_reason=v.reason, next_retry_at=v.retry
            FROM (VALUES $values) v(id,status,completed,failures,reason,retry)
            WHERE r.id=v.id RETURNING r.id
        """,
            )
        batch.forEachIndexed { i, r ->
            query
                .param("id$i", r.id)
                .param("status$i", r.status)
                .param("completed$i", r.completed?.let(Timestamp::from))
                .param("failures$i", r.failures)
                .param("reason$i", r.reason)
                .param("retry$i", Timestamp.from(r.retry))
        }
        return query
            .query(String::class.java)
            .list()
            .filterNotNull()
            .toSet()
    }

    /** Stops admissions, flushes pending requests, and waits up to 30 seconds for the writer. */
    override fun close() {
        synchronized(admission) { accepting.set(false) }
        worker.interrupt()
        worker.join(30000)
        check(!worker.isAlive) { "Completion writer did not finish within 30 seconds" }
    }
}
