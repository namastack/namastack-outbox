package io.namastack.performance.tooling.run

internal data class RunDefinition(
    val runId: String,
    val mode: String,
    val profile: String,
    val expectedRecords: Long,
    val recordsPerKey: Int,
    val consumerInstances: Int,
    val batchSize: Int,
    val pollInterval: String,
    val warmupRecords: Long,
    val status: String,
    val producerTargetRate: Long? = null,
    val producerDurationMs: Long? = null,
    val producerBatchSize: Int? = null,
    val producerWorkers: Int? = null,
    val measurementWarmupMs: Long? = null,
    val minProducerRateRatio: Double? = null,
    val maxBacklogGrowthRate: Double? = null,
    val maxEndBacklog: Long? = null,
)
