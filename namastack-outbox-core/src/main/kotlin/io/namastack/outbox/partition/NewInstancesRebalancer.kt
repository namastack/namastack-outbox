package io.namastack.outbox.partition

import org.slf4j.LoggerFactory
import kotlin.math.max

class NewInstancesRebalancer(
    private val partitionAssignmentRepository: PartitionAssignmentRepository,
) {
    private val log = LoggerFactory.getLogger(NewInstancesRebalancer::class.java)

    fun rebalance(
        partitionContext: PartitionContext,
        previousActiveInstanceIds: Set<String>,
    ) {
        val currentInstanceId = partitionContext.currentInstanceId
        val newInstanceIds = partitionContext.activeInstanceIds - previousActiveInstanceIds
        if (newInstanceIds.isEmpty()) return

        val partitionsToReleaseCount =
            max(0, partitionContext.countOwnedPartitionAssignments() - partitionContext.targetPartitionCount)
        if (partitionsToReleaseCount == 0) return

        val partitionToRelease =
            partitionContext
                .getAllPartitionNumbersByInstanceId(currentInstanceId)
                .sorted()
                .takeLast(partitionsToReleaseCount)

        partitionToRelease.forEach { partitionNumber ->
            try {
                partitionAssignmentRepository.releasePartition(partitionNumber, currentInstanceId)
            } catch (_: Exception) {
                log.warn("Could not release partition $partitionNumber from active instance $currentInstanceId")
            }
        }

        log.debug("Successfully released {} partitions of {}", partitionsToReleaseCount, currentInstanceId)
    }
}
