package io.namastack.performance.tooling.jdbc

import io.namastack.performance.tooling.record.PerformanceRecord
import io.namastack.performance.tooling.run.RunDefinition
import io.namastack.performance.tooling.run.RunInfo
import io.namastack.performance.tooling.internal.ceilDiv
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp

internal fun PreparedStatement.bind(definition: RunDefinition) {
    var index = 1
    setString(index++, definition.runId)
    setString(index++, definition.mode)
    setString(index++, definition.profile)
    setLong(index++, definition.expectedRecords)
    setLong(index++, ceilDiv(definition.expectedRecords, definition.recordsPerKey.toLong()))
    setInt(index++, definition.recordsPerKey)
    setInt(index++, definition.consumerInstances)
    setInt(index++, definition.batchSize)
    setString(index++, definition.pollInterval)
    setLong(index++, definition.warmupRecords)
    setString(index++, definition.status)
    setObject(index++, definition.producerTargetRate)
    setObject(index++, definition.producerDurationMs)
    setObject(index++, definition.producerBatchSize)
    setObject(index++, definition.producerWorkers)
    setObject(index++, definition.measurementWarmupMs)
    setObject(index++, definition.minProducerRateRatio)
    setObject(index++, definition.maxBacklogGrowthRate)
    setObject(index, definition.maxEndBacklog)
}

internal fun PreparedStatement.bind(record: PerformanceRecord) {
    setString(1, record.id)
    setString(2, record.status)
    setString(3, record.recordKey)
    setString(4, record.recordType)
    setString(5, record.payload)
    setString(6, record.context)
    setTimestamp(7, Timestamp.from(record.createdAt))
    setTimestamp(8, null)
    setInt(9, record.failureCount)
    setString(10, null)
    setTimestamp(11, Timestamp.from(record.nextRetryAt))
    setInt(12, record.partition)
    setString(13, record.handlerId)
    addBatch()
}

internal fun ResultSet.toRunInfo() =
    RunInfo(
        runId = getString("run_id"),
        mode = getString("mode"),
        profile = getString("profile"),
        expectedRecords = getLong("expected_records"),
        distinctKeys = getLong("distinct_keys"),
        recordsPerKey = getInt("records_per_key"),
        consumerInstances = getInt("consumer_instances"),
        batchSize = getInt("batch_size"),
        pollInterval = getString("poll_interval"),
        warmupRecords = getLong("warmup_records"),
        status = getString("status"),
        startedAt = instant("started_at"),
        lastCompletedAt = instant("last_completed_at"),
        triggerDurationMs = nullableLong("trigger_duration_ms"),
        producerTargetRate = nullableLong("producer_target_rate"),
        producerDurationMs = nullableLong("producer_duration_ms"),
        producerBatchSize = nullableInt("producer_batch_size"),
        producerWorkers = nullableInt("producer_workers"),
        producerStartedAt = instant("producer_started_at"),
        producerCompletedAt = instant("producer_completed_at"),
        producedRecords = nullableLong("produced_records"),
        measurementWarmupMs = nullableLong("measurement_warmup_ms"),
        minProducerRateRatio = nullableDouble("min_producer_rate_ratio"),
        maxBacklogGrowthRate = nullableDouble("max_backlog_growth_rate"),
        maxEndBacklog = nullableLong("max_end_backlog"),
    )

private fun ResultSet.instant(column: String) = getTimestamp(column)?.toInstant()

private fun ResultSet.nullableLong(column: String) = getLong(column).takeUnless { wasNull() }

private fun ResultSet.nullableInt(column: String) = getInt(column).takeUnless { wasNull() }

private fun ResultSet.nullableDouble(column: String) = getDouble(column).takeUnless { wasNull() }
