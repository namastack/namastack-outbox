package io.namastack.performance.tooling.report

import io.namastack.outbox.partition.PartitionHasher

internal data class PartitionStats(
    val usedPartitions: Long,
    val minRecords: Long,
    val avgRecords: Double,
    val maxRecords: Long,
) {
    fun toMap() = mapOf("usedPartitions" to usedPartitions, "totalPartitions" to PartitionHasher.TOTAL_PARTITIONS, "minRecords" to minRecords, "avgRecords" to avgRecords, "maxRecords" to maxRecords)
}
