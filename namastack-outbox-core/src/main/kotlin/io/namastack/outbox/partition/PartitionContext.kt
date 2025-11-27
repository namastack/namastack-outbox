package io.namastack.outbox.partition

import kotlin.math.max

/**
 * Represents a snapshot of all partition assignments and instance states for a single rebalance execution.
 * Provides fast, in-memory queries for partitioning, rebalancing, and monitoring without additional DB access.
 *
 * @property currentInstanceId The id of the instance performing the rebalance.
 * @property activeInstanceIds The set of all currently active instance ids.
 * @property partitionAssignments The full set of persisted partition assignments.
 * @property targetPartitionCount The desired partition count for the current instance.
 */
data class PartitionContext(
    val currentInstanceId: String,
    val activeInstanceIds: Set<String>,
    val partitionAssignments: Set<PartitionAssignment>,
) {
    /**
     * The number of partitions this instance should own according to the distribution algorithm.
     */
    val targetPartitionCount: Int = DistributionCalculator.targetCount(currentInstanceId, activeInstanceIds)

    /**
     * Returns true if no partition assignments exist (initial bootstrap case).
     */
    fun hasNoPartitionAssignments(): Boolean = partitionAssignments.isEmpty()

    /**
     * Returns the number of partitions currently owned by this instance.
     */
    fun countOwnedPartitionAssignments(): Int = partitionAssignments.count { it.instanceId == currentInstanceId }

    /**
     * Returns the number of partitions that should be released by this instance to reach a balanced state.
     */
    fun countPartitionsToRelease(): Int = max(0, countOwnedPartitionAssignments() - targetPartitionCount)

    /**
     * Returns the number of partitions that should be claimed by this instance to reach a balanced state.
     */
    fun countPartitionsToClaim(): Int = max(0, targetPartitionCount - countOwnedPartitionAssignments())

    /**
     * Returns all partition assignments owned by the given instance (unsorted).
     * @param instanceId The id of the instance.
     */
    fun getPartitionAssignmentsByInstanceId(instanceId: String): Set<PartitionAssignment> =
        partitionAssignments
            .filter { assignment -> assignment.instanceId == instanceId }
            .toSet()

    /**
     * Returns all partition assignments owned by the current instance.
     */
    fun getOwnedPartitionAssignments(): Set<PartitionAssignment> =
        getPartitionAssignmentsByInstanceId(currentInstanceId)

    /**
     * Returns all partition assignments whose owner is not among active instances (stale candidates).
     */
    fun getStalePartitionAssignments(): Set<PartitionAssignment> =
        partitionAssignments.filterNot { it.instanceId in activeInstanceIds }.toSet()

    /**
     * Returns all partition numbers that are currently unassigned (instanceId == null).
     */
    fun getUnassignedPartitionNumbers(): List<Int> =
        partitionAssignments
            .filter { it.instanceId == null }
            .map { it.partitionNumber }
            .sorted()

    /**
     * Returns the partition assignments that should be released by the current instance to reach a balanced state.
     * Only returns partitions owned by the current instance. The last N partitions (by partition number) are selected,
     * where N is the number of partitions to release.
     *
     * @return Set of partition assignments to release
     */
    fun getPartitionAssignmentsToRelease(): Set<PartitionAssignment> {
        val count = countPartitionsToRelease()
        if (count == 0) return emptySet()

        return getOwnedPartitionAssignments()
            .sortedBy { it.partitionNumber }
            .takeLast(count)
            .toSet()
    }

    /**
     * Returns the partition assignments that should be claimed by the current instance to reach a balanced state.
     * Only returns stale or unassigned partitions. The first N partitions (by partition number) are selected,
     * where N is the number of partitions to claim.
     *
     * Returns an empty set if:
     * - No partitions need to be claimed (already at target count)
     * - No stale partitions are available
     * - Fewer stale partitions are available than needed
     *
     * @return Set of partition assignments to claim
     */
    fun getPartitionAssignmentsToClaim(): Set<PartitionAssignment> {
        val partitionsToClaimCount = countPartitionsToClaim()
        if (partitionsToClaimCount == 0) return emptySet()

        val stalePartitionAssignments = getStalePartitionAssignments()
        if (stalePartitionAssignments.isEmpty()) return emptySet()

        if (partitionsToClaimCount > stalePartitionAssignments.size) return emptySet()

        return stalePartitionAssignments
            .sortedBy { it.partitionNumber }
            .take(partitionsToClaimCount)
            .toSet()
    }

    /**
     * Returns statistics about the current partition assignment state, including:
     * - total number of partitions
     * - total number of instances
     * - average partitions per instance
     * - per-instance partition counts
     * - count and list of unassigned partitions
     *
     * Useful for monitoring, diagnostics, and balancing decisions.
     */
    fun getPartitionStats(): PartitionStats {
        val instanceStats = calculateInstanceStats(partitionAssignments)
        val unassignedPartitionNumbers = getUnassignedPartitionNumbers()
        val totalAssignedPartitions = partitionAssignments.size - unassignedPartitionNumbers.size
        val totalUnassignedPartitions = unassignedPartitionNumbers.size
        val totalPartitions = partitionAssignments.size
        val totalInstances = instanceStats.size
        val avgPartitionsPerInstance =
            if (totalInstances > 0) {
                totalAssignedPartitions.toDouble() / totalInstances
            } else {
                0.0
            }

        return PartitionStats(
            totalPartitions = totalPartitions,
            totalInstances = totalInstances,
            averagePartitionsPerInstance = avgPartitionsPerInstance,
            instanceStats = calculateInstanceStats(partitionAssignments),
            unassignedPartitionsCount = totalUnassignedPartitions,
            unassignedPartitionNumbers = unassignedPartitionNumbers,
        )
    }

    /**
     * Count how many partitions each instance owns.
     * @param assignments map of partitionNumber -> instanceId
     */
    private fun calculateInstanceStats(assignments: Set<PartitionAssignment>): Map<String, Int> =
        assignments
            .filter { it.instanceId != null }
            .groupingBy { it.instanceId!! }
            .eachCount()
}
