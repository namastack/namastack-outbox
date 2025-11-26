package io.namastack.outbox

import io.namastack.outbox.OutboxRecordStatus.NEW
import io.namastack.outbox.instance.OutboxInstanceRegistry
import io.namastack.outbox.partition.PartitionCoordinator
import io.namastack.outbox.partition.PartitionProcessingStats

/**
 * Provider for outbox partition processing metrics.
 *
 * This provider collects statistics about partition assignments and pending
 * record counts for the current instance, enabling monitoring of load
 * distribution and processing bottlenecks.
 *
 * @param recordRepository Repository for accessing outbox records
 * @param partitionCoordinator Coordinator for partition assignments
 * @param instanceRegistry Registry for instance management
 *
 * @author Roland Beisel
 * @since 0.2.0
 */
class OutboxPartitionMetricsProvider(
    private val recordRepository: OutboxRecordRepository,
    private val partitionCoordinator: PartitionCoordinator,
    private val instanceRegistry: OutboxInstanceRegistry,
) {
    /**
     * Gets statistics about current partition processing load.
     *
     * @return Statistics containing partition assignments and pending record counts
     */
    fun getProcessingStats(): PartitionProcessingStats {
        val myInstanceId = instanceRegistry.getCurrentInstanceId()
        val assignedPartitions = partitionCoordinator.getAssignedPartitionNumbers()

        val pendingRecordsPerPartition =
            assignedPartitions.associateWith { partition ->
                recordRepository.countRecordsByPartition(partition, NEW)
            }

        val totalPendingRecords = pendingRecordsPerPartition.values.sum()

        return PartitionProcessingStats(
            instanceId = myInstanceId,
            assignedPartitions = assignedPartitions.sorted(),
            pendingRecordsPerPartition = pendingRecordsPerPartition,
            totalPendingRecords = totalPendingRecords,
        )
    }

    /**
     * Gets cluster-wide partition distribution statistics.
     *
     * @return Statistics about partition distribution across all instances
     */
    fun getPartitionStats() = partitionCoordinator.getPartitionContext().getPartitionStats()
}
