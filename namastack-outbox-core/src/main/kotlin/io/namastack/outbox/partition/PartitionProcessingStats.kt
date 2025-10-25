package io.namastack.outbox.partition

/**
 * Statistics about partition processing for an instance.
 */
data class PartitionProcessingStats(
    val instanceId: String,
    val assignedPartitions: List<Int>,
    val pendingRecordsPerPartition: Map<Int, Long>,
    val totalPendingRecords: Long,
)
