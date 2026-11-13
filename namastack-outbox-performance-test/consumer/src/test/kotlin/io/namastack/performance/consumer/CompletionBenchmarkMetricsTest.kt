package io.namastack.performance.consumer

import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.completion.OutboxCompletion
import io.namastack.outbox.completion.OutboxCompletionPersistence
import io.namastack.outbox.completion.OutboxCompletionWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CompletionBenchmarkMetricsTest {
    @Test
    fun `writer metrics count batch sizes and returned failures without changing the result`() {
        val metrics = CompletionBenchmarkMetrics()
        val results = listOf(Result.success(Unit), Result.failure<Unit>(IllegalStateException("failed")))
        val writer = metrics.postProcessAfterInitialization(writer(results), "writer") as OutboxCompletionWriter

        assertSame(
            results,
            writer.writeDeletions(
                listOf(OutboxCompletion.Delete("run-record-1"), OutboxCompletion.Delete("run-record-2")),
            ),
        )

        val stats = metrics.snapshot("run")["writer"] as Map<*, *>
        assertEquals(1L, stats["calls"])
        assertEquals(2L, stats["records"])
        assertEquals(1L, stats["failedRecords"])
        assertEquals(mapOf("2" to 1L), stats["batchSizeCounts"])
    }

    @Test
    fun `warmup and measured completions have separate counters`() {
        val metrics = CompletionBenchmarkMetrics()
        val writer =
            metrics.postProcessAfterInitialization(
                writer(listOf(Result.success(Unit))),
                "writer",
            ) as OutboxCompletionWriter

        writer.writeDeletions(listOf(OutboxCompletion.Delete("run-warmup-record-1")))
        writer.writeDeletions(listOf(OutboxCompletion.Delete("run-record-1")))

        assertEquals(1L, (metrics.snapshot("run")["writer"] as Map<*, *>)["records"])
        assertEquals(1L, (metrics.snapshot("run-warmup")["writer"] as Map<*, *>)["records"])
    }

    @Test
    fun `completion failures are counted and propagated unchanged`() {
        val metrics = CompletionBenchmarkMetrics()
        val failure = IllegalStateException("failed")
        val persistence =
            object : OutboxCompletionPersistence {
                override fun updateProcessingState(record: OutboxRecord<*>) = Unit

                override fun deleteCompleted(id: String): Unit = throw failure
            }
        val measured = metrics.postProcessAfterInitialization(persistence, "persistence") as OutboxCompletionPersistence

        assertSame(
            failure,
            assertThrows(IllegalStateException::class.java) { measured.deleteCompleted("run-record-1") },
        )
        assertEquals(1L, (metrics.snapshot("run")["completion"] as Map<*, *>)["failedRecords"])
    }

    private fun writer(results: List<Result<Unit>>) =
        object : OutboxCompletionWriter {
            override fun writeUpdates(updates: List<OutboxCompletion.Update>) = results

            override fun writeDeletions(deletions: List<OutboxCompletion.Delete>) = results
        }
}
