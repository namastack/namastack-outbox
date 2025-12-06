package io.namastack.outbox.partition

/**
 * Statistics about the current state of partition distribution across instances.
 *
 * Provides a snapshot of how partitions are distributed, including load balancing metrics
 * and unassigned partition information. Used for monitoring and administrative purposes.
 *
 * @property totalPartitions Total number of partitions in the system (always 256)
 * @property totalInstances Number of active instances in the cluster
 * @property averagePartitionsPerInstance Theoretical average partitions per instance (totalPartitions / totalInstances)
 * @property instanceStats Map of instance IDs to their assigned partition counts
 * @property unassignedPartitionsCount Number of partitions currently unassigned to any instance
 * @property unassignedPartitionNumbers Sorted list of partition numbers that are currently unassigned
 *
 * ## Example
 *
 * With 5 instances and 256 total partitions:
 * - averagePartitionsPerInstance = 51.2 (256 / 5)
 * - Some instances may have 51, others 52 partitions due to fair distribution algorithm
 * - unassignedPartitionsCount = 0 (in normal operation, should be empty or minimal)
 *
 * @author Roland Beisel
 * @since 0.4.0
 */
data class PartitionStats(
    val totalPartitions: Int,
    val totalInstances: Int,
    val averagePartitionsPerInstance: Double,
    val instanceStats: Map<String, Int>,
    val unassignedPartitionsCount: Int,
    val unassignedPartitionNumbers: List<Int>,
)
