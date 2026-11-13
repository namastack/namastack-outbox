package io.namastack.performance.tooling.run

import java.time.Duration

internal data class ProduceRequest(
    val runId: String,
    val profile: String,
    val targetRate: Long,
    val duration: Duration,
    val transactionBatchSize: Int,
    val workers: Int,
    val recordsPerKey: Int,
    val measurementWarmup: Duration,
    val minimumProducerRateRatio: Double,
    val maximumBacklogGrowthRate: Double,
    val maximumEndBacklog: Long,
    val consumerInstances: Int,
    val consumerBatchSize: Int,
    val pollInterval: String,
    val warmupRecords: Long,
)
