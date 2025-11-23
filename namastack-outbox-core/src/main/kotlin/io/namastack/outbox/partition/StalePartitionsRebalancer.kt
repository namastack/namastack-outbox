package io.namastack.outbox.partition

import org.slf4j.LoggerFactory
import kotlin.math.max

/**
 * Claims partitions from instances that are no longer active (stale).
 *
 * Steps:
 *  1. Compute shortage (target - owned) -> number of partitions needed.
 *  2. Collect stale assignments (owner not in active set) in ascending order.
 *  3. Select up to shortage count and attempt atomic claim via repository.
 *  4. Log success or silent downgrade on failure (another instance may win).
 *
 * Concurrency:
 *  - A claim may fail if partitions were reassigned concurrently; this is acceptable.
 *  - Method is idempotent: zero shortage or no stale partitions => no-op.
 */
class StalePartitionsRebalancer(
    private val partitionAssignmentRepository: PartitionAssignmentRepository,
) {
    private val log = LoggerFactory.getLogger(StalePartitionsRebalancer::class.java)

    /**
     * Reclaim stale partitions up to the current shortage for this instance.
     * @param partitionContext snapshot of current assignments and targets
     */
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
            return
        }

        log.debug(
            "Successfully claimed {} partitions for current instance {}: {}",
            partitionsToClaimCount,
            currentInstanceId,
            partitionsToClaim,
        )
    }
}
