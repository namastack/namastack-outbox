package io.namastack.outbox.partition

/**
 * Snapshot of partition state used within one rebalance execution.
 * Provides fast in-memory queries without additional DB access.
 *
 * @property currentInstanceId id of the instance performing the rebalance
 * @property activeInstanceIds all currently active instance ids
 * @property partitionAssignments full set of persisted partition assignments
 * @property targetPartitionCount desired partition count for current instance
 */
data class PartitionContext(
    val currentInstanceId: String,
    val activeInstanceIds: Set<String>,
    val partitionAssignments: Set<PartitionAssignment>,
    val targetPartitionCount: Int,
) {
    /** Returns true if no partition assignments exist (initial bootstrap case). */
    fun hasNoPartitionAssignments(): Boolean = partitionAssignments.isEmpty()

    /** Counts partitions owned by the current instance. */
    fun countOwnedPartitionAssignments(): Int = partitionAssignments.count { it.instanceId == currentInstanceId }

    /** Lists all partition numbers owned by the given instance (unsorted). */
    fun getAllPartitionNumbersByInstanceId(instanceId: String): List<Int> =
        partitionAssignments
            .filter { assignment -> assignment.instanceId == instanceId }
            .map { it.partitionNumber }

    /** Returns partitions whose owner is not among active instances (stale candidates). */
    fun getStalePartitionAssignments(): Set<PartitionAssignment> =
        partitionAssignments.filterNot { it.instanceId in activeInstanceIds }.toSet()
}
