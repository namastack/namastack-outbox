package io.namastack.performance.tooling.run

import io.namastack.outbox.partition.PartitionHasher
import io.namastack.performance.tooling.internal.ceilDiv
import io.namastack.performance.tooling.internal.elapsedMillis
import io.namastack.performance.tooling.internal.secondsBetween
import io.namastack.performance.tooling.internal.sleepUntil
import io.namastack.performance.tooling.record.performanceRecord
import io.namastack.performance.tooling.store.PerformanceStore
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

internal class PerformanceTestService(
    private val store: PerformanceStore,
) {
    fun seed(request: SeedRequest): SeedResult {
        require(request.records > 0) { "--records must be greater than zero" }
        require(request.recordsPerKey > 0) { "--records-per-key must be greater than zero" }
        val startedAt = System.nanoTime()
        store.saveRun(
            RunDefinition(
                runId = request.runId,
                mode = "backlog-drain",
                profile = request.profile,
                expectedRecords = request.records,
                recordsPerKey = request.recordsPerKey,
                consumerInstances = request.consumerInstances,
                batchSize = request.batchSize,
                pollInterval = request.pollInterval,
                warmupRecords = request.warmupRecords,
                status = "SEEDING",
            ),
        )
        val createdAt = Instant.now()
        val records =
            sequence {
                for (index in 0 until request.records) {
                    yield(performanceRecord(request.runId, index, request.recordsPerKey, createdAt))
                }
            }
        val copied = store.replaceStagedRecords(request.runId, records)
        store.markSeeded(request.runId)
        return SeedResult(copied, ceilDiv(request.records, request.recordsPerKey.toLong()), elapsedMillis(startedAt))
    }

    fun trigger(
        runId: String,
        timeout: Duration,
    ) = store.trigger(runId, timeout)

    fun produce(request: ProduceRequest): ProduceResult {
        val expectedRecords = request.targetRate * request.duration.toMillis() / 1_000
        require(request.targetRate > 0) { "--producer-rate must be greater than zero" }
        require(request.duration > Duration.ZERO) { "--producer-duration must be greater than zero" }
        require(request.transactionBatchSize > 0) { "--producer-batch-size must be greater than zero" }
        require(request.workers > 0) { "--producer-workers must be greater than zero" }
        require(request.recordsPerKey > 0) { "--records-per-key must be greater than zero" }
        require(expectedRecords > 0) { "Rate and duration must schedule at least one record" }
        require(request.measurementWarmup < request.duration) { "--measurement-warmup must be shorter than --producer-duration" }

        val startedAt =
            store.beginProduction(
                RunDefinition(
                    runId = request.runId,
                    mode = "steady-state",
                    profile = request.profile,
                    expectedRecords = expectedRecords,
                    recordsPerKey = request.recordsPerKey,
                    consumerInstances = request.consumerInstances,
                    batchSize = request.consumerBatchSize,
                    pollInterval = request.pollInterval,
                    warmupRecords = request.warmupRecords,
                    status = "PREPARING",
                    producerTargetRate = request.targetRate,
                    producerDurationMs = request.duration.toMillis(),
                    producerBatchSize = request.transactionBatchSize,
                    producerWorkers = request.workers,
                    measurementWarmupMs = request.measurementWarmup.toMillis(),
                    minProducerRateRatio = request.minimumProducerRateRatio,
                    maxBacklogGrowthRate = request.maximumBacklogGrowthRate,
                    maxEndBacklog = request.maximumEndBacklog,
                ),
            )
        produceRecords(request, expectedRecords)
        val completedAt = store.markProduced(request.runId, expectedRecords)
        val duration = secondsBetween(startedAt, completedAt)
        return ProduceResult(expectedRecords, expectedRecords, startedAt, completedAt, duration, expectedRecords / duration)
    }

    fun awaitProcessing(
        expectedRecords: Long,
        timeout: Duration,
    ): StatusCounts {
        val deadline = Instant.now().plus(timeout)
        while (Instant.now().isBefore(deadline)) {
            val counts = store.statusCounts()
            if (counts.isTerminal(expectedRecords)) {
                check(counts.isValid(expectedRecords)) { "Processing completed with failures or retries" }
                return counts
            }
            Thread.sleep(500)
        }
        error("Timed out waiting for $expectedRecords records")
    }

    fun awaitCluster(
        instances: Int,
        timeout: Duration,
    ): ClusterStatus {
        val deadline = Instant.now().plus(timeout)
        var stableChecks = 0
        while (Instant.now().isBefore(deadline)) {
            val status = store.clusterStatus()
            if (status.activeInstances == instances.toLong() && status.assignedPartitions == PartitionHasher.TOTAL_PARTITIONS.toLong()) {
                if (++stableChecks >= 2) return status
            } else {
                stableChecks = 0
            }
            Thread.sleep(1_000)
        }
        error("Timed out waiting for $instances active consumers and ${PartitionHasher.TOTAL_PARTITIONS} assigned partitions")
    }

    fun resetOutbox() = store.resetOutbox()

    private fun produceRecords(
        request: ProduceRequest,
        expectedRecords: Long,
    ) {
        val cursor = AtomicLong()
        val startedAtNanos = System.nanoTime()
        val executor = Executors.newFixedThreadPool(request.workers)
        try {
            val futures =
                (1..request.workers).map {
                    executor.submit(
                        Callable {
                            produceRecords(request, expectedRecords, cursor, startedAtNanos)
                        },
                    )
                }
            futures.forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun produceRecords(
        request: ProduceRequest,
        expectedRecords: Long,
        cursor: AtomicLong,
        startedAtNanos: Long,
    ) {
        store.openRecordWriter().use { writer ->
            while (true) {
                val firstIndex = cursor.getAndAdd(request.transactionBatchSize.toLong())
                if (firstIndex >= expectedRecords) break
                sleepUntil(startedAtNanos + firstIndex * 1_000_000_000L / request.targetRate)
                val count = min(request.transactionBatchSize.toLong(), expectedRecords - firstIndex).toInt()
                writer.append(
                    List(count) { offset ->
                        performanceRecord(request.runId, firstIndex + offset, request.recordsPerKey, Instant.now())
                    },
                )
            }
        }
    }
}
