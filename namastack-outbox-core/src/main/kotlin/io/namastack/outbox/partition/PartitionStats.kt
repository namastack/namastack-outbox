package io.namastack.outbox.partition

/**
 * Statistics about partition distribution.
 */
data class PartitionStats(
    val totalPartitions: Int,
    val totalInstances: Int,
    val averagePartitionsPerInstance: Double,
    val instanceStats: Map<String, Int>,
)
