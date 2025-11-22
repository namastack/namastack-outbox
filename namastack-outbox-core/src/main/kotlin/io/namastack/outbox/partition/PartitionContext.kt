package io.namastack.outbox.partition

data class PartitionContext(
    val currentInstanceId: String,
    val activeInstanceIds: Set<String>,
    val partitionAssignments: Set<PartitionAssignment>,
    val targetPartitionCount: Int,
) {
    fun hasNoPartitionAssignments(): Boolean = partitionAssignments.isEmpty()

    fun countOwnedPartitionAssignments(): Int = partitionAssignments.count { it.instanceId == currentInstanceId }

    fun getAllPartitionNumbersByInstanceId(instanceId: String): List<Int> =
        partitionAssignments
            .filter { partitionAssignment -> partitionAssignment.instanceId == instanceId }
            .map { it.partitionNumber }

    fun getStalePartitionAssignments(): Set<PartitionAssignment> =
        partitionAssignments.filterNot { it.instanceId in activeInstanceIds }.toSet()
}
