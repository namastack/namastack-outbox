package io.namastack.outbox.partition

import io.namastack.outbox.OutboxInstanceRegistry
import org.slf4j.LoggerFactory

/**
 * Central orchestrator for partition ownership management.
 *
 * Responsibilities:
 *  - Bootstrap: claim all partitions if none exist yet.
 *  - Rebalance: reclaim stale partitions and release surplus when topology changes.
 *  - Caching: memorizes owned partition numbers until next rebalance.
 *  - Stats: provides aggregated partition distribution metrics.
 *
 * Concurrency assumptions:
 *  - Rebalance invoked after batch completion (scheduler guarantees no overlapping processing).
 *  - Ownership changes only through this coordinator / repository layer.
 */
class PartitionCoordinator(
    private val instanceRegistry: OutboxInstanceRegistry,
    private val partitionAssignmentRepository: PartitionAssignmentRepository,
    private val stalePartitionRebalancer: StalePartitionsRebalancer =
        StalePartitionsRebalancer(
            partitionAssignmentRepository,
        ),
    private val newInstanceRebalancer: NewInstancesRebalancer =
        NewInstancesRebalancer(
            partitionAssignmentRepository,
        ),
) {
    private val log = LoggerFactory.getLogger(PartitionCoordinator::class.java)
    private val currentInstanceId = instanceRegistry.getCurrentInstanceId()
    private var lastKnownInstanceIds: Set<String> = emptySet()
    private var cachedAssignedPartitions: List<Int>? = null

    /**
     * Perform a rebalance cycle:
     *  1. Fetch active instance IDs.
     *  2. Build immutable context snapshot.
     *  3. Bootstrap if no assignments exist.
     *  4. Claim stale partitions, then release surplus for new instances.
     *  5. Invalidate cached partition list.
     */
    fun rebalance() {
        log.debug("Starting rebalance for instance {}", currentInstanceId)

        val activeInstanceIds = instanceRegistry.getActiveInstanceIds()
        if (activeInstanceIds.isEmpty()) {
            log.warn("No active instances found.")
            return
        }

        val partitionAssignments = partitionAssignmentRepository.findAll()
        val targetPartitionCount = DistributionCalculator.targetCount(currentInstanceId, activeInstanceIds)

        val partitionContext =
            PartitionContext(
                currentInstanceId = currentInstanceId,
                activeInstanceIds = activeInstanceIds,
                partitionAssignments = partitionAssignments,
                targetPartitionCount = targetPartitionCount,
            )

        if (partitionContext.hasNoPartitionAssignments()) {
            bootstrapPartitions()
            return
        }

        stalePartitionRebalancer.rebalance(partitionContext)
        newInstanceRebalancer.rebalance(partitionContext, lastKnownInstanceIds)

        lastKnownInstanceIds = activeInstanceIds
        cachedAssignedPartitions = null
    }

    /**
     * Return currently owned partition numbers (cached until next rebalance).
     * Cache is invalidated after each successful rebalance.
     */
    fun getAssignedPartitionNumbers(): List<Int> =
        cachedAssignedPartitions
            ?: partitionAssignmentRepository
                .findByInstanceId(currentInstanceId)
                .map { it.partitionNumber }
                .toList()
                .also { cachedAssignedPartitions = it }

    /**
     * Compute distribution statistics including unassigned partitions and per-instance counts.
     * Returned object can be used for monitoring/metrics.
     */
    fun getPartitionStats(): PartitionStats {
        val allAssignments = partitionAssignmentRepository.findAll()
        val partitionAssignments: Map<Int, String> =
            allAssignments
                .filter { it.instanceId != null }
                .associate { it.partitionNumber to it.instanceId!! }

        val unassignedPartitionNumbers =
            allAssignments
                .filter { it.instanceId == null }
                .map { it.partitionNumber }
                .sorted()

        val instanceStats = calculateInstanceStats(partitionAssignments)
        val totalAssignedPartitions = partitionAssignments.size
        val totalUnassignedPartitions = unassignedPartitionNumbers.size
        val totalPartitions = totalAssignedPartitions + totalUnassignedPartitions
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
            instanceStats = instanceStats,
            unassignedPartitionsCount = totalUnassignedPartitions,
            unassignedPartitionNumbers = unassignedPartitionNumbers,
        )
    }

    /**
     * Attempt to claim all partitions for this instance during initial startup.
     * Failures are silently ignored (another instance may have bootstrapped concurrently).
     */
    private fun bootstrapPartitions() {
        try {
            partitionAssignmentRepository.claimAllPartitions(currentInstanceId)
            log.debug("Successfully bootstrapped and claimed all partitions for instance {}", currentInstanceId)
        } catch (_: Exception) {
            log.debug("Could not claim all partitions for instance {}", currentInstanceId)
        }
    }

    /**
     * Count how many partitions each instance owns.
     * @param assignments map of partitionNumber -> instanceId
     */
    private fun calculateInstanceStats(assignments: Map<Int, String>): Map<String, Int> =
        assignments.values.groupingBy { it }.eachCount()
}
