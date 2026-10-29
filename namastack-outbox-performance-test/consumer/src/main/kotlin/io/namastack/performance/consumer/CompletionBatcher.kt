package io.namastack.performance.consumer

import io.namastack.outbox.OutboxRecord
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.SqlArrayValue
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Experimental synchronous acknowledgement with cross-key database batching.
 * A key cannot advance until its completion write has committed, keeping its normal replay window.
 */
internal class CompletionBatcher(
    private val jdbc: JdbcClient,
    private val transactions: TransactionTemplate,
    private val batchSize: Int,
    private val waitMicros: Long,
    private val useArrays: Boolean = false,
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

    private val queue = LinkedBlockingQueue<Request>(4096)
    private val accepting = AtomicBoolean(true)
    private val admission = Any()
    private val worker: Thread

    init {
        require(batchSize in 1..512 && waitMicros >= 0)
        worker = Thread.ofVirtual().name("outbox-completion-batcher").start { run() }
    }

    fun complete(record: OutboxRecord<*>): Boolean {
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
                val deadline = System.nanoTime() + TimeUnit.MICROSECONDS.toNanos(waitMicros)
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
                    val committed = transactions.execute { writeBatch(batch) }!!
                    batch.forEach { it.ack.complete(it.id in committed) }
                } catch (ex: Throwable) {
                    batch.forEach { it.ack.completeExceptionally(ex) }
                    if (ex is Error) throw ex
                }
            }
        } finally {
            accepting.set(false)
            val abandoned = mutableListOf<Request>()
            queue.drainTo(abandoned)
            abandoned.forEach { it.ack.completeExceptionally(IllegalStateException("Completion writer stopped")) }
        }
    }

    private fun writeBatch(batch: List<Request>): Set<String> {
        if (useArrays) return writeArrayBatch(batch)
        val values =
            batch.indices.joinToString(",") { i ->
                "(CAST(:id$i AS varchar),CAST(:status$i AS varchar),CAST(:completed$i AS timestamptz)," +
                    "CAST(:failures$i AS integer),CAST(:reason$i AS varchar),CAST(:retry$i AS timestamptz))"
            }
        val query =
            jdbc.sql(
                """
            UPDATE outbox_record r SET status=v.status, completed_at=v.completed, failure_count=v.failures,
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

    private fun writeArrayBatch(batch: List<Request>): Set<String> =
        jdbc
            .sql(
                """
        UPDATE outbox_record r SET status=v.status, completed_at=v.completed, failure_count=v.failures,
            failure_reason=v.reason, next_retry_at=v.retry
        FROM unnest(CAST(:ids AS varchar[]),CAST(:statuses AS varchar[]),CAST(:completed AS timestamptz[]),
            CAST(:failures AS integer[]),CAST(:reasons AS varchar[]),CAST(:retries AS timestamptz[]))
            v(id,status,completed,failures,reason,retry)
        WHERE r.id=v.id RETURNING r.id
    """,
            ).param("ids", sqlArray("varchar", batch.map { it.id }))
            .param("statuses", sqlArray("varchar", batch.map { it.status }))
            .param("completed", sqlArray("timestamptz", batch.map { it.completed?.toString() }))
            .param("failures", sqlArray("integer", batch.map { it.failures }))
            .param("reasons", sqlArray("varchar", batch.map { it.reason }))
            .param("retries", sqlArray("timestamptz", batch.map { it.retry.toString() }))
            .query(String::class.java)
            .list()
            .filterNotNull()
            .toSet()

    private fun sqlArray(
        type: String,
        values: List<Any?>,
    ): SqlArrayValue {
        // JDBC permits NULL array elements; Spring's Java vararg annotation is stricter.
        @Suppress("UNCHECKED_CAST")
        val elements = values.toTypedArray() as Array<Any>
        return SqlArrayValue(type, *elements)
    }

    override fun close() {
        synchronized(admission) { accepting.set(false) }
        worker.interrupt()
        worker.join(30000)
        check(!worker.isAlive) { "Completion writer did not finish within 30 seconds" }
    }
}
