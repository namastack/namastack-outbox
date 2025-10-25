package io.namastack.outbox.partition

import io.namastack.outbox.OutboxInstanceRegistry
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import java.util.concurrent.ConcurrentHashMap

/**
 * Coordinates partition assignment across multiple outbox processor instances.
 *
 * Handles automatic rebalancing when instances join or leave the cluster,
 * ensuring even distribution of partitions for optimal load balancing.
 *
 * @param instanceRegistry Registry for managing active instances
 *
 * @author Roland Beisel
 * @since 0.2.0
 */
class PartitionCoordinator(
    private val instanceRegistry: OutboxInstanceRegistry,
) {
    private val log = LoggerFactory.getLogger(PartitionCoordinator::class.java)

    // Cache for current partition assignments
    private val partitionAssignments = ConcurrentHashMap<Int, String>()
    private var lastKnownInstances: Set<String> = emptySet()

    /**
     * Gets the partitions assigned to a specific instance.
     *
     * @param instanceId The instance ID to get partitions for
     * @return List of partition numbers assigned to the instance
     */
    fun getAssignedPartitions(instanceId: String): List<Int> {
        ensurePartitionsAssigned()

        return partitionAssignments.entries
            .filter { it.value == instanceId }
            .map { it.key }
            .sorted()
    }

    /**
     * Gets all current partition assignments.
     *
     * @return Map of partition number to instance ID
     */
    fun getCurrentAssignments(): Map<Int, String> {
        ensurePartitionsAssigned()
        return HashMap(partitionAssignments)
    }

    /**
     * Gets the instance ID that owns a specific partition.
     *
     * @param partition The partition number
     * @return Instance ID that owns the partition, or null if not assigned
     */
    fun getInstanceForPartition(partition: Int): String? {
        ensurePartitionsAssigned()
        return partitionAssignments[partition]
    }

    /**
     * Checks if any partition assignments need to be updated.
     */
    @Scheduled(fixedRate = 15000) // Every 15 seconds
    fun checkForRebalancing() {
        try {
            val currentInstances = instanceRegistry.getActiveInstanceIds()

            if (currentInstances != lastKnownInstances) {
                log.info("🔄 Instance change detected: {} -> {}", lastKnownInstances, currentInstances)
                rebalancePartitions(currentInstances)
                lastKnownInstances = currentInstances
            }
        } catch (ex: Exception) {
            log.error("Error during partition rebalancing check", ex)
        }
    }

    /**
     * Ensures that partitions are assigned if not already done.
     */
    private fun ensurePartitionsAssigned() {
        if (partitionAssignments.isEmpty()) {
            val currentInstances = instanceRegistry.getActiveInstanceIds()
            if (currentInstances.isNotEmpty()) {
                rebalancePartitions(currentInstances)
            }
        }
    }

    /**
     * Rebalances partitions across the given set of instances.
     *
     * @param instances Set of active instance IDs
     */
    private fun rebalancePartitions(instances: Set<String>) {
        if (instances.isEmpty()) {
            log.warn("⚠️ No active instances available for partition assignment")
            partitionAssignments.clear()
            return
        }

        val sortedInstances = instances.sorted() // Consistent ordering
        val oldAssignments = HashMap(partitionAssignments)

        log.info(
            "🎯 Rebalancing {} partitions across {} instances",
            PartitionHasher.TOTAL_PARTITIONS,
            instances.size,
        )

        // Calculate new assignments using round-robin
        val newAssignments = calculatePartitionAssignments(sortedInstances)

        // Log changes
        logPartitionChanges(oldAssignments, newAssignments)

        // Update assignments
        partitionAssignments.clear()
        partitionAssignments.putAll(newAssignments)

        log.info("✅ Partition rebalancing completed")
    }

    /**
     * Calculates optimal partition assignments for the given instances.
     *
     * @param sortedInstances List of instance IDs sorted consistently
     * @return Map of partition to instance assignments
     */
    private fun calculatePartitionAssignments(sortedInstances: List<String>): Map<Int, String> {
        val assignments = mutableMapOf<Int, String>()

        // Round-robin assignment
        for (partition in 0 until PartitionHasher.TOTAL_PARTITIONS) {
            val instanceIndex = partition % sortedInstances.size
            assignments[partition] = sortedInstances[instanceIndex]
        }

        return assignments
    }

    /**
     * Logs partition assignment changes for monitoring.
     *
     * @param oldAssignments Previous partition assignments
     * @param newAssignments New partition assignments
     */
    private fun logPartitionChanges(
        oldAssignments: Map<Int, String>,
        newAssignments: Map<Int, String>,
    ) {
        var transferred = 0
        var newPartitions = 0

        newAssignments.forEach { (partition, newInstance) ->
            val oldInstance = oldAssignments[partition]
            when {
                oldInstance == null -> newPartitions++
                oldInstance != newInstance -> transferred++
            }
        }

        if (transferred > 0 || newPartitions > 0) {
            log.info(
                "📊 Partition changes: {} transferred, {} new assignments",
                transferred,
                newPartitions,
            )
        }

        // Log per-instance summary
        val instanceStats = calculateInstanceStats(newAssignments)
        instanceStats.forEach { (instanceId, count) ->
            log.info("📋 Instance {} assigned {} partitions", instanceId, count)
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

    /**
     * Gets statistics about current partition distribution.
     *
     * @return PartitionStats object with distribution information
     */
    fun getPartitionStats(): PartitionStats {
        ensurePartitionsAssigned()

        val instanceStats = calculateInstanceStats(partitionAssignments)
        val totalPartitions = partitionAssignments.size
        val totalInstances = instanceStats.size
        val avgPartitionsPerInstance =
            if (totalInstances > 0) {
                totalPartitions.toDouble() / totalInstances
            } else {
                0.0
            }

        return PartitionStats(
            totalPartitions = totalPartitions,
            totalInstances = totalInstances,
            averagePartitionsPerInstance = avgPartitionsPerInstance,
            instanceStats = instanceStats,
        )
    }
}
