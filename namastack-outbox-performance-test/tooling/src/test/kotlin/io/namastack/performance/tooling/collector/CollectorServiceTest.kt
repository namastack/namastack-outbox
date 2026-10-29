package io.namastack.performance.tooling.collector

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.namastack.performance.tooling.report.LatencyStats
import io.namastack.performance.tooling.report.PerformanceReportWriter
import io.namastack.performance.tooling.report.ReportContext
import io.namastack.performance.tooling.report.SteadyStateStats
import io.namastack.performance.tooling.run.RunInfo
import io.namastack.performance.tooling.run.StatusCounts
import io.namastack.performance.tooling.store.PerformanceStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

class CollectorServiceTest {
    @Test
    fun `retained history cannot make undelivered live records appear processed`() {
        val (counts, stats) = collect(StatusCounts(100, 10000, 0, 0, null))
        assertEquals(100, counts.completedRecords)
        assertEquals(0.0, stats.processingRateDuringProduction)
        assertEquals(100, stats.backlogAtProducerStop)
        assertFalse(stats.sustainable)
    }

    @Test
    fun `retained history is excluded from delivered throughput and final correctness`() {
        val (counts, stats) = collect(StatusCounts(0, 10100, 0, 0, null))
        assertEquals(100, counts.completedRecords)
        assertEquals(100.0, stats.processingRateDuringProduction)
        assertEquals(0, stats.backlogAtProducerStop)
    }

    @Test
    fun `missing retained fixtures fail instead of reporting false capacity`() {
        assertThrows(IllegalArgumentException::class.java) {
            collect(StatusCounts(100, 9999, 0, 0, null))
        }
    }

    private fun collect(productionCounts: StatusCounts): Pair<StatusCounts, SteadyStateStats> {
        val store = mockk<PerformanceStore>()
        val reports = mockk<PerformanceReportWriter>()
        val started = Instant.now().minusSeconds(1)
        val run =
            RunInfo(
                runId = "live",
                mode = "steady-state",
                profile = "test",
                expectedRecords = 100,
                distinctKeys = 10,
                recordsPerKey = 10,
                consumerInstances = 1,
                batchSize = 100,
                pollInterval = "25ms",
                warmupRecords = 0,
                status = "PRODUCED",
                startedAt = started,
                lastCompletedAt = started.plusSeconds(1),
                triggerDurationMs = null,
                producerTargetRate = 100,
                producerDurationMs = 1000,
                producerBatchSize = 100,
                producerWorkers = 1,
                producerStartedAt = started,
                producerCompletedAt = started.plusSeconds(1),
                producedRecords = 100,
                measurementWarmupMs = 0,
                minProducerRateRatio = 0.99,
                maxBacklogGrowthRate = 1.0,
                maxEndBacklog = 10,
            )
        every { store.findRun("live") } returnsMany
            listOf(run.copy(status = "RUNNING"), run, run.copy(status = "VALID"))
        every { store.statusCountsAt(any()) } returns productionCounts
        every { store.statusCounts() } returns StatusCounts(0, 10100, 0, 0, run.lastCompletedAt)
        every { store.latencyStats("live") } returns LatencyStats(0.0, 0.0, 0.0, 0.0, 0.0)
        every { store.partitionStats("live") } returns mockk(relaxed = true)
        every { store.environmentInfo() } returns mockk(relaxed = true)
        justRun { store.completeRun(any(), any()) }
        val counts = slot<StatusCounts>()
        val stats = slot<SteadyStateStats>()
        justRun { reports.writeSteadyStateReport(any(), any(), capture(counts), any(), capture(stats), any(), any()) }
        every { reports.steadyStateSummary(any(), any(), any()) } returns "done"

        CollectorService(store, reports).collectSteadyState(
            ReportContext("live", Path.of("."), "", "", Duration.ofSeconds(5), Duration.ofMillis(1), 10000),
        )
        return counts.captured to stats.captured
    }
}
