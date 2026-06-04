package io.namastack.performance.tooling.store

import io.namastack.performance.tooling.record.PerformanceRecord
import io.namastack.performance.tooling.report.EnvironmentInfo
import io.namastack.performance.tooling.report.LatencyStats
import io.namastack.performance.tooling.report.PartitionStats
import io.namastack.performance.tooling.run.ClusterStatus
import io.namastack.performance.tooling.run.RunDefinition
import io.namastack.performance.tooling.run.RunInfo
import io.namastack.performance.tooling.run.StatusCounts
import io.namastack.performance.tooling.run.TriggerResult
import java.time.Duration
import java.time.Instant

internal interface PerformanceStore {
    fun saveRun(definition: RunDefinition)

    fun replaceStagedRecords(
        runId: String,
        records: Sequence<PerformanceRecord>,
    ): Long

    fun markSeeded(runId: String)

    fun trigger(
        runId: String,
        timeout: Duration,
    ): TriggerResult

    fun beginProduction(definition: RunDefinition): Instant

    fun openRecordWriter(): OutboxRecordWriter

    fun markProduced(
        runId: String,
        producedRecords: Long,
    ): Instant

    fun findRun(runId: String): RunInfo?

    fun statusCounts(): StatusCounts

    fun statusCountsAt(timestamp: Instant): StatusCounts

    fun clusterStatus(): ClusterStatus

    fun resetOutbox()

    fun completeRun(
        runId: String,
        counts: StatusCounts,
    )

    fun latencyStats(runId: String): LatencyStats

    fun partitionStats(): PartitionStats

    fun environmentInfo(): EnvironmentInfo
}
