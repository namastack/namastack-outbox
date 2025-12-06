package io.namastack.outbox.partition

/**
 * Statistics about partition processing load for a specific instance.
 *
 * Provides metrics about the current instance's partition assignments and
 * the number of pending records waiting to be processed in each partition.
 * Used for load monitoring, alerting, and performance analysis.
 *
 * @property instanceId The ID of the instance these statistics are for
 * @property assignedPartitions Sorted list of partition numbers assigned to this instance
 * @property pendingRecordsPerPartition Map of partition number to count of NEW/unprocessed records
 * @property totalPendingRecords Total count of pending records across all assigned partitions
 *
 * ## Processing Load Indicators
 *
 * - **High totalPendingRecords**: Backlog of work, may indicate slowness or high throughput
 * - **Uneven distribution in pendingRecordsPerPartition**: Some partitions may have more traffic than others
 * - **Max pending in single partition**: Can indicate hot partitions that need monitoring
 *
 * ## Example
 *
 * ```
 * assignedPartitions = [0, 1, 2, 3, 4]
 * pendingRecordsPerPartition = {0: 10, 1: 8, 2: 15, 3: 5, 4: 7}
 * totalPendingRecords = 45
 * ```
 *
 * @author Roland Beisel
 * @since 0.4.0
 */
data class PartitionProcessingStats(
    val instanceId: String,
    val assignedPartitions: List<Int>,
    val pendingRecordsPerPartition: Map<Int, Long>,
    val totalPendingRecords: Long,
)
