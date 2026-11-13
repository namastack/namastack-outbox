package io.namastack.performance.tooling.benchmark

import io.namastack.performance.tooling.jdbc.bind
import io.namastack.performance.tooling.record.PerformanceRecord
import io.namastack.performance.tooling.record.performanceRecord
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal val READ_PAYLOAD = "benchmark-payload-" + "0123456789abcdef".repeat(10)

/** Deterministic fixtures keep every record of a key in the same producer transaction. */
internal class JdbcBenchmarkFixture(
    private val database: JdbcBenchmarkDatabase,
) {
    private val insertSql =
        """
        INSERT INTO outbox_record
        (id,status,record_key,record_type,payload,context,created_at,completed_at,
         failure_count,failure_reason,next_retry_at,partition_no,handler_id)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
        """.trimIndent()

    fun seed(
        history: Long,
        records: Long,
        recordsPerKey: Int,
        batchSize: Int,
        workers: Int,
        runId: String,
    ) {
        require(history >= 0 && records >= 0 && recordsPerKey > 0 && workers > 0 && batchSize > 0)
        require(batchSize % recordsPerKey == 0 && records % recordsPerKey == 0L)
        val now = Instant.now()
        val createdBase = now.minusSeconds(records / recordsPerKey + 3600)
        insert(history, batchSize, workers) { index ->
            performanceRecord("history", index, recordsPerKey, now.minusSeconds(86400)).copy(
                status = "COMPLETED",
                completedAt = now.minusSeconds(86400),
                recordKey = "history:${index / recordsPerKey}",
            )
        }
        // Publish history-only statistics first, matching an outbox that becomes busy after idling.
        database.analyze()
        insert(records, batchSize, workers) { index ->
            readRecord(runId, index, recordsPerKey, now, createdBase)
        }
        database.write(
            "fixture.json",
            mapOf(
                "clock" to now.toString(),
                "historyRecords" to history,
                "fixtureRecords" to records,
                "recordsPerKey" to recordsPerKey,
                "runId" to runId,
            ),
        )
    }

    private fun readRecord(
        runId: String,
        index: Long,
        perKey: Int,
        now: Instant,
        createdBase: Instant,
    ): PerformanceRecord {
        val key = index / perKey
        val first = index % perKey == 0L
        return performanceRecord(runId, index, perKey, createdBase.plusSeconds(key)).copy(
            recordType = String::class.java.name,
            payload = database.mapper.writeValueAsString(READ_PAYLOAD),
            status =
                when {
                    first && key % 10 == 0L -> "FAILED"
                    first && key % 10 == 2L -> "COMPLETED"
                    else -> "NEW"
                },
            completedAt = if (first && key % 10 == 2L) now.minusSeconds(1) else null,
            nextRetryAt =
                if ((first && key % 10 == 1L) ||
                    (index % perKey == 1L && key % 10 == 3L)
                ) {
                    now.plusSeconds(86400)
                } else {
                    now.minusSeconds(1)
                },
        )
    }

    private fun insert(
        count: Long,
        batchSize: Int,
        workers: Int,
        record: (Long) -> PerformanceRecord,
    ) {
        if (count == 0L) return
        val threads = Executors.newFixedThreadPool(workers)
        try {
            val futures =
                (0 until workers).map { worker ->
                    threads.submit {
                        database.connection().use { connection ->
                            connection.autoCommit = false
                            connection.prepareStatement(insertSql).use { statement ->
                                var offset = worker.toLong() * batchSize
                                while (offset < count) {
                                    val end = minOf(offset + batchSize, count)
                                    for (index in offset until end) statement.bind(record(index))
                                    statement.executeBatch()
                                    statement.clearBatch()
                                    connection.commit()
                                    offset += workers.toLong() * batchSize
                                }
                            }
                        }
                    }
                }
            futures.forEach { it.get(15, TimeUnit.MINUTES) }
        } finally {
            threads.shutdownNow()
        }
    }
}
