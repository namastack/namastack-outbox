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

        // Shortage (how many partitions we still need); non-negative.
        val partitionsToClaimCount = max(0, targetPartitionCount - ownedPartitionCount)
        if (partitionsToClaimCount == 0) return

        // Stale partitions ordered by partition number for deterministic selection.
        val stalePartitionAssignments =
            partitionContext.getStalePartitionAssignments().sortedBy { it.partitionNumber }

        if (stalePartitionAssignments.isEmpty()) return

        // Take only what we need (up to shortage) and convert to a set for repository call.
        val partitionsToClaim =
            stalePartitionAssignments
                .map { it.partitionNumber }
                .sorted()
                .take(partitionsToClaimCount)
                .toSet()

        if (partitionsToClaim.isEmpty()) return

        // Capture stale instance ids (may be null -> only claim unassigned).
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
