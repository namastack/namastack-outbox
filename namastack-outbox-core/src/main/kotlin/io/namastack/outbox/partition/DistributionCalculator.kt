package io.namastack.outbox.partition

import io.namastack.outbox.partition.PartitionHasher.TOTAL_PARTITIONS

object DistributionCalculator {
    fun targetCount(
        instanceId: String,
        activeInstanceIds: Set<String>,
    ): Int {
        if (activeInstanceIds.isEmpty()) return 0

        val sortedActiveInstanceIds = activeInstanceIds.sorted()
        val size = sortedActiveInstanceIds.size
        val index = sortedActiveInstanceIds.indexOf(instanceId)

        val base = TOTAL_PARTITIONS / size
        val remainder = TOTAL_PARTITIONS % size

        return if (index < remainder) base + 1 else base
    }
}
