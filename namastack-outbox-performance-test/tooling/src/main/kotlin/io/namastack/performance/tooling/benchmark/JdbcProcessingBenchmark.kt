package io.namastack.performance.tooling.benchmark

import io.namastack.performance.tooling.jdbc.JdbcCopyOutboxRecordWriter
import io.namastack.performance.tooling.jdbc.JdbcOutboxRecordWriter
import io.namastack.performance.tooling.record.performanceRecord
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/** Measures durable completions while a time-bounded producer feeds real scheduler instances. */
internal class JdbcProcessingBenchmark(
    private val database: JdbcBenchmarkDatabase,
) {
    fun awaitCluster(instances: Int) {
        val deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos()
        database.connection().use { connection ->
            while (System.nanoTime() < deadline) {
                val assigned =
                    connection.createStatement().use { statement ->
                        statement
                            .executeQuery(
                                """
                                SELECT i.instance_id, COUNT(p.partition_number)
                                FROM outbox_instance i
                                LEFT JOIN outbox_partition p ON p.instance_id = i.instance_id
                                WHERE i.status = 'ACTIVE'
                                GROUP BY i.instance_id
                                """.trimIndent(),
                            ).use { rows ->
                                buildList { while (rows.next()) add(rows.getInt(2)) }
                            }
                    }
                val minimum = 256 / instances
                val maximum = (256 + instances - 1) / instances
                if (assigned.size == instances &&
                    assigned.sum() == 256 &&
                    assigned.all { it in minimum..maximum }
                ) {
                    return
                }
                Thread.sleep(500)
            }
        }
        error("Cluster did not assign all partitions to $instances active instances")
    }

    fun run(
        runId: String,
        duration: Duration,
        rate: Long,
        perKey: Int,
        batchSize: Int,
        workers: Int,
        delete: Boolean,
        label: String,
    ) {
        require(rate > 0 && perKey > 0 && workers > 0 && !duration.isNegative && !duration.isZero)
        require(batchSize > 0 && batchSize % perKey == 0)
        val nextRecord = AtomicLong()
        val committed = AtomicLong()
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val threads = Executors.newFixedThreadPool(workers)
        var started = 0L
        val futures =
            (0 until workers).map {
                threads.submit {
                    val writer =
                        if (database.name == "postgres") {
                            JdbcCopyOutboxRecordWriter(database.connection())
                        } else {
                            JdbcOutboxRecordWriter(database.connection())
                        }
                    writer.use {
                        ready.countDown()
                        check(start.await(2, TimeUnit.MINUTES)) { "Producer was not started" }
                        val deadline = started + duration.toNanos()
                        while (System.nanoTime() < deadline) {
                            val first = nextRecord.getAndAdd(batchSize.toLong())
                            val release = started + (first.toDouble() / rate * 1e9).toLong()
                            if (release >= deadline) break
                            while (System.nanoTime() < release) {
                                LockSupport.parkNanos(minOf(release - System.nanoTime(), 10_000_000))
                            }
                            if (System.nanoTime() >= deadline) break
                            val createdAt = Instant.now()
                            writer.append(
                                (first until first + batchSize).map { index ->
                                    performanceRecord(runId, index, perKey, createdAt)
                                },
                            )
                            committed.addAndGet(batchSize.toLong())
                        }
                    }
                }
            }
        try {
            check(ready.await(60, TimeUnit.SECONDS)) { "Could not open all producer connections" }
            started = System.nanoTime()
            start.countDown()
            futures.forEach { it.get(duration.toSeconds() + 120, TimeUnit.SECONDS) }
            val producerSeconds = (System.nanoTime() - started) / 1e9
            // A final paced batch can commit before the deadline. Still measure the whole requested window.
            val deadline = started + duration.toNanos()
            while (System.nanoTime() < deadline) {
                LockSupport.parkNanos(minOf(deadline - System.nanoTime(), 10_000_000))
            }
            val snapshotStarted = System.nanoTime()
            val counts = counts(runId)
            val snapshotFinished = System.nanoTime()
            val processed = if (delete) committed.get() - counts.getValue("remaining") else counts.getValue("completed")
            val measurementSeconds = (snapshotFinished - started) / 1e9
            val conservativeRate = processed / measurementSeconds
            val producerTargetMet =
                committed.get() >= rate * duration.toMillis() / 1000.0 * .99 &&
                    committed.get() / producerSeconds >= rate * .99
            val capacityScreenPassed =
                producerTargetMet &&
                    counts.getValue("failed") == 0L &&
                    counts.getValue("pending") <= rate * 2 &&
                    conservativeRate >= rate * .97
            check(processed >= 0) { "Count exceeds committed producer input" }
            val result =
                mapOf(
                    "database" to database.name,
                    "mode" to "processing",
                    "deleteCompleted" to delete,
                    "targetRecordsPerSecond" to rate,
                    "requestedSeconds" to duration.toMillis() / 1000.0,
                    "producerSeconds" to producerSeconds,
                    "measurementSeconds" to measurementSeconds,
                    "producedRecords" to committed.get(),
                    "actualProducerRate" to committed.get() / producerSeconds,
                    "producerTargetMet" to producerTargetMet,
                    "capacityScreenPassed" to capacityScreenPassed,
                    "processedAtStopSnapshot" to processed,
                    "pendingAtStopSnapshot" to counts.getValue("pending"),
                    "failedAtStopSnapshot" to counts.getValue("failed"),
                    "remainingAtStopSnapshot" to counts.getValue("remaining"),
                    "snapshotMilliseconds" to (snapshotFinished - snapshotStarted) / 1e6,
                    "processedPerSecondConservative" to conservativeRate,
                    "countBoundary" to
                        "Snapshot after the requested duration and producer commits; elapsed includes the read-committed count query",
                    "completionEvidence" to
                        if (delete) {
                            "Committed input minus physically remaining run rows"
                        } else {
                            "Persisted COMPLETED status"
                        },
                )
            database.write("processing-$label.json", result)
            println(database.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result))
        } finally {
            start.countDown()
            threads.shutdownNow()
        }
    }

    fun awaitDrain(runId: String) {
        val deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos()
        while (System.nanoTime() < deadline) {
            val counts = counts(runId)
            check(counts.getValue("failed") == 0L) { "Failed records while warming up" }
            if (counts.getValue("pending") == 0L) return
            Thread.sleep(500)
        }
        error("Warm-up did not drain within two minutes")
    }

    private fun counts(runId: String): Map<String, Long> =
        database.connection().use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT COUNT(*),
                        COALESCE(SUM(CASE WHEN status = 'NEW' THEN 1 ELSE 0 END), 0),
                        COALESCE(SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END), 0),
                        COALESCE(SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END), 0)
                    FROM outbox_record WHERE id LIKE ? ESCAPE '!'
                    """.trimIndent(),
                ).use { statement ->
                    statement.queryTimeout = 60
                    val escaped = runId.replace("!", "!!").replace("%", "!%").replace("_", "!_")
                    statement.setString(1, "$escaped-record-%")
                    statement.executeQuery().use {
                        check(it.next())
                        mapOf(
                            "remaining" to it.getLong(1),
                            "pending" to it.getLong(2),
                            "completed" to it.getLong(3),
                            "failed" to it.getLong(4),
                        )
                    }
                }
        }
}
