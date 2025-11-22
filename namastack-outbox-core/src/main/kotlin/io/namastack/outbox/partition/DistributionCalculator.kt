package io.namastack.outbox.partition

import io.namastack.outbox.partition.PartitionHasher.TOTAL_PARTITIONS

/**
 * Calculates the fair target partition count for an instance.
 * Instances are sorted lexicographically; the first 'remainder' entries each get one extra partition.
 * Assumes instanceId is contained in activeInstanceIds.
 */
object DistributionCalculator {
    /**
     * Compute target number of partitions for the given instance.
     * @return 0 if no active instances.
     */
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
