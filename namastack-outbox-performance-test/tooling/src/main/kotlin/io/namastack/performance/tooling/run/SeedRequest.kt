package io.namastack.performance.tooling.run

internal data class SeedRequest(
    val runId: String,
    val profile: String,
    val records: Long,
    val recordsPerKey: Int,
    val consumerInstances: Int,
    val batchSize: Int,
    val pollInterval: String,
    val warmupRecords: Long,
)
