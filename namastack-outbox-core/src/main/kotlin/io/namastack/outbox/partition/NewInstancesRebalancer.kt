package io.namastack.outbox.partition

import org.slf4j.LoggerFactory
import kotlin.math.max

/**
 * Handles rebalancing triggered by newly joined instances.
 *
 * Responsibility:
 * - Detects new instance IDs compared to the previous snapshot.
 * - If current instance owns more partitions than its target share, releases the surplus.
 * - Chooses the highest partition numbers (sorted, tail) to free for better distribution stability.
 * - Performs a bulk release; if that fails (e.g. transient DB issue) logs a warning and continues.
 *
 * Concurrency:
 * Only the owning instance attempts release. A concurrent prior release simply reduces affected rows.
 * Idempotent: If no new instances or no surplus -> immediate no-op.
 */
class NewInstancesRebalancer(
    private val partitionAssignmentRepository: PartitionAssignmentRepository,
) {
    private val log = LoggerFactory.getLogger(NewInstancesRebalancer::class.java)

    /**
     * Release surplus partitions when new instances appear.
     *
     * @param partitionContext current ownership & target distribution
     * @param previousActiveInstanceIds instance IDs known in the last rebalance cycle
     */
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
                .toSet()

        if (partitionToRelease.isEmpty()) return

        try {
            partitionAssignmentRepository.releasePartitions(partitionToRelease, currentInstanceId)
        } catch (_: Exception) {
            log.warn("Could not release partitions $partitionToRelease from active instance $currentInstanceId")
        }

        log.debug("Successfully released {} partitions of {}", partitionsToReleaseCount, currentInstanceId)
    }
}
