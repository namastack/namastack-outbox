package io.namastack.outbox.partition

import io.namastack.outbox.OutboxInstanceRegistry
import org.slf4j.LoggerFactory

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
            log.debug("Successfully bootstrapped and claimed all partitions for instance {}", currentInstanceId)
            return
        }

        stalePartitionRebalancer.rebalance(partitionContext)
        newInstanceRebalancer.rebalance(partitionContext, lastKnownInstanceIds)

        lastKnownInstanceIds = activeInstanceIds
        cachedAssignedPartitions = null
    }

    fun getAssignedPartitionNumbers(): List<Int> =
        cachedAssignedPartitions
            ?: partitionAssignmentRepository
                .findByInstanceId(currentInstanceId)
                .map { it.partitionNumber }
                .toList()
                .also { cachedAssignedPartitions = it }

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

    private fun bootstrapPartitions() {
        try {
            partitionAssignmentRepository.claimAllPartitions(currentInstanceId)
        } catch (_: Exception) {
        }
    }

    /**
     * Calculates how many partitions each instance has been assigned.
     *
     * @param assignments Current partition assignments
     * @return Map of instance ID to partition count
     */
    private fun calculateInstanceStats(assignments: Map<Int, String>): Map<String, Int> =
        assignments.values.groupingBy { it }.eachCount()
}
