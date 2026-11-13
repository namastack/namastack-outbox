package io.namastack.performance.tooling.report

import io.namastack.performance.tooling.run.RunInfo
import io.namastack.performance.tooling.run.StatusCounts

internal interface PerformanceReportWriter {
    fun writeDrainReport(
        context: ReportContext,
        run: RunInfo,
        counts: StatusCounts,
        samples: List<BacklogSample>,
        partitions: PartitionStats,
        environment: EnvironmentInfo,
    )

    fun writeSteadyStateReport(
        context: ReportContext,
        run: RunInfo,
        counts: StatusCounts,
        samples: List<BacklogSample>,
        stats: SteadyStateStats,
        partitions: PartitionStats,
        environment: EnvironmentInfo,
    )

    fun drainSummary(
        run: RunInfo,
        counts: StatusCounts,
    ): String

    fun steadyStateSummary(
        run: RunInfo,
        counts: StatusCounts,
        stats: SteadyStateStats,
    ): String
}
