package io.namastack.performance.tooling.collector

import io.namastack.performance.tooling.report.BacklogSample
import io.namastack.performance.tooling.report.LatencyStats
import io.namastack.performance.tooling.report.PerformanceReportWriter
import io.namastack.performance.tooling.store.PerformanceStore
import io.namastack.performance.tooling.report.ReportContext
import io.namastack.performance.tooling.run.RunInfo
import io.namastack.performance.tooling.run.StatusCounts
import io.namastack.performance.tooling.report.SteadyStateStats
import io.namastack.performance.tooling.internal.secondsBetween
import java.time.Duration
import java.time.Instant
import kotlin.math.max

internal class CollectorService(
    private val store: PerformanceStore,
    private val reports: PerformanceReportWriter,
) {
    fun collectDrain(context: ReportContext): String {
        val samples = mutableListOf<BacklogSample>()
        var run = waitForRunningRun(context.runId, Instant.now().plus(context.timeout))
        lateinit var finalCounts: StatusCounts
        while (Instant.now().isBefore(run.startedAt!!.plus(context.timeout))) {
            finalCounts = store.statusCounts()
            samples += BacklogSample(Instant.now(), "DRAIN", finalCounts)
            if (finalCounts.isTerminal(run.expectedRecords)) break
            Thread.sleep(context.sampleInterval.toMillis())
        }
        check(finalCounts.isTerminal(run.expectedRecords)) { "Timed out collecting '${run.runId}'" }
        store.completeRun(run.runId, finalCounts)
        run = loadRun(run.runId)
        reports.writeDrainReport(context, run, finalCounts, samples, store.partitionStats(), store.environmentInfo())
        check(finalCounts.isValid(run.expectedRecords)) { "Performance run '${run.runId}' is invalid" }
        return reports.drainSummary(run, finalCounts)
    }

    fun collectSteadyState(context: ReportContext): String {
        val samples = mutableListOf<BacklogSample>()
        var run = waitForRunningRun(context.runId, Instant.now().plus(Duration.ofMinutes(2)))
        check(run.mode == "steady-state")
        val producerDeadline = Instant.now().plusMillis(run.producerDurationMs!!).plus(Duration.ofMinutes(2))
        while (true) {
            run = loadRun(run.runId)
            if (run.status == "PRODUCED") break
            samples += BacklogSample(Instant.now(), "PRODUCTION", store.statusCounts())
            check(Instant.now().isBefore(producerDeadline)) { "Timed out waiting for producer '${run.runId}'" }
            Thread.sleep(context.sampleInterval.toMillis())
        }
        val productionEndCounts = store.statusCountsAt(run.producerCompletedAt!!)
        samples += BacklogSample(run.producerCompletedAt, "PRODUCTION_END", productionEndCounts)

        val drainDeadline = Instant.now().plus(context.timeout)
        var finalCounts = store.statusCounts()
        while (!finalCounts.isTerminal(run.expectedRecords) && Instant.now().isBefore(drainDeadline)) {
            samples += BacklogSample(Instant.now(), "DRAIN", finalCounts)
            Thread.sleep(context.sampleInterval.toMillis())
            finalCounts = store.statusCounts()
        }
        samples += BacklogSample(Instant.now(), "DRAIN_END", finalCounts)
        check(finalCounts.isTerminal(run.expectedRecords)) { "Timed out draining '${run.runId}'" }
        store.completeRun(run.runId, finalCounts)
        run = loadRun(run.runId)
        val ordered = samples.sortedBy { it.timestamp }
        val stats = steadyStateStats(run, productionEndCounts, ordered, store.latencyStats(run.runId))
        reports.writeSteadyStateReport(context, run, finalCounts, ordered, stats, store.partitionStats(), store.environmentInfo())
        check(finalCounts.isValid(run.expectedRecords)) { "Performance run '${run.runId}' is invalid" }
        return reports.steadyStateSummary(run, finalCounts, stats)
    }

    private fun waitForRunningRun(
        runId: String,
        deadline: Instant,
    ): RunInfo {
        while (Instant.now().isBefore(deadline)) {
            val run = store.findRun(runId)
            if (run?.status == "RUNNING") return run
            Thread.sleep(250)
        }
        error("Timed out waiting for run '$runId' to start")
    }

    private fun loadRun(runId: String) = checkNotNull(store.findRun(runId)) { "Unknown run '$runId'" }

    private fun steadyStateStats(
        run: RunInfo,
        productionEnd: StatusCounts,
        samples: List<BacklogSample>,
        latency: LatencyStats,
    ): SteadyStateStats {
        val startedAt = run.producerStartedAt!!
        val completedAt = run.producerCompletedAt!!
        val duration = secondsBetween(startedAt, completedAt)
        val measurementStart = startedAt.plusMillis(run.measurementWarmupMs!!)
        val productionSamples =
            samples
                .filter { it.phase == "PRODUCTION" || it.phase == "PRODUCTION_END" }
                .filter { !it.timestamp.isBefore(startedAt) && !it.timestamp.isAfter(completedAt) }
        val stabilized = productionSamples.filter { !it.timestamp.isBefore(measurementStart) }.ifEmpty { productionSamples }
        val produced = run.producedRecords!!
        val actualRate = produced / duration
        val rateCompliant = actualRate >= run.producerTargetRate!! * run.minProducerRateRatio!!
        val backlogGrowth = linearRegressionSlope(stabilized.map { it.timestamp to it.counts.newRecords.toDouble() })
        val backlogAtStop = produced - productionEnd.completedRecords - productionEnd.failedRecords
        val maximumBacklog = max(productionSamples.maxOfOrNull { it.counts.newRecords } ?: 0, backlogAtStop)
        val drainDuration = max(0.0, secondsBetween(completedAt, run.lastCompletedAt!!))
        val sustainable = rateCompliant && backlogGrowth <= run.maxBacklogGrowthRate!! && backlogAtStop <= run.maxEndBacklog!!
        return SteadyStateStats(
            actualProducerDurationSeconds = duration,
            actualProducerRate = actualRate,
            producerRateCompliant = rateCompliant,
            processingRateDuringProduction = productionEnd.completedRecords / duration,
            stabilizedProcessingRate = averageRate(stabilized) { it.counts.completedRecords },
            backlogGrowthRate = backlogGrowth,
            backlogAtProducerStop = backlogAtStop,
            maximumBacklog = maximumBacklog,
            drainDurationSeconds = drainDuration,
            sustainable = sustainable,
            latency = latency,
            productionSamples = productionSamples,
        )
    }

    private fun averageRate(
        samples: List<BacklogSample>,
        value: (BacklogSample) -> Long,
    ): Double {
        val rates =
            samples.sortedBy { it.timestamp }.zipWithNext().mapNotNull { (before, after) ->
                val seconds = secondsBetween(before.timestamp, after.timestamp)
                if (seconds <= 0) null else (value(after) - value(before)) / seconds
            }
        return if (rates.isEmpty()) 0.0 else rates.average()
    }

    private fun linearRegressionSlope(points: List<Pair<Instant, Double>>): Double {
        if (points.size < 2) return 0.0
        val startedAt = points.first().first
        val values = points.map { secondsBetween(startedAt, it.first) to it.second }
        val avgX = values.map { it.first }.average()
        val avgY = values.map { it.second }.average()
        val denominator = values.sumOf { (x, _) -> (x - avgX) * (x - avgX) }
        return if (denominator == 0.0) 0.0 else values.sumOf { (x, y) -> (x - avgX) * (y - avgY) } / denominator
    }
}
