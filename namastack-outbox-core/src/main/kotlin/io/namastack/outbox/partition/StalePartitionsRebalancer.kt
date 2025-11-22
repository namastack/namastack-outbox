package io.namastack.outbox.partition

import org.slf4j.LoggerFactory
import kotlin.math.max

class StalePartitionsRebalancer(
    private val partitionAssignmentRepository: PartitionAssignmentRepository,
) {
    private val log = LoggerFactory.getLogger(StalePartitionsRebalancer::class.java)

    fun rebalance(partitionContext: PartitionContext) {
        val ownedPartitionCount = partitionContext.countOwnedPartitionAssignments()
        val targetPartitionCount = partitionContext.targetPartitionCount

        val partitionsToClaimCount = max(0, targetPartitionCount - ownedPartitionCount)
        if (partitionsToClaimCount == 0) return

        val stalePartitionAssignments =
            partitionContext.getStalePartitionAssignments().sortedBy { it.partitionNumber }

        if (stalePartitionAssignments.isEmpty()) return

        val partitionsToClaim =
            stalePartitionAssignments
                .map { it.partitionNumber }
                .sorted()
                .take(partitionsToClaimCount)
                .toSet()

        if (partitionsToClaim.isEmpty()) return

        val staleInstanceIds = stalePartitionAssignments.mapNotNull { it.instanceId }.toSet().ifEmpty { null }
        val currentInstanceId = partitionContext.currentInstanceId

        try {
            partitionAssignmentRepository.claimStalePartitions(
                partitionIds = partitionsToClaim,
                staleInstanceIds = staleInstanceIds,
                newInstanceId = currentInstanceId,
            )
        } catch (_: Exception) {
            log.debug("Could not claim partitions {} for current instance {}", partitionsToClaim, currentInstanceId)
        }

        log.debug(
            "Successfully claimed {} partitions for current instance {}",
            partitionsToClaimCount,
            currentInstanceId,
        )
    }
}
