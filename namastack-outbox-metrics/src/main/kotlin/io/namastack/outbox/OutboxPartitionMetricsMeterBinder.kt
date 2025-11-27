package io.namastack.outbox

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import io.namastack.outbox.partition.PartitionHasher

/**
 * Micrometer meter binder for outbox partition metrics.
 *
 * This binder registers gauges for tracking partition-level metrics
 * including assigned partitions count, pending records per partition,
 * and total pending records for the current instance.
 *
 * @param partitionMetricsProvider Provider for partition processing statistics
 *
 * @author Roland Beisel
 * @since 0.2.0
 */
class OutboxPartitionMetricsMeterBinder(
    private val partitionMetricsProvider: OutboxPartitionMetricsProvider,
) : MeterBinder {
    /**
     * Binds outbox partition metrics to the provided meter registry.
     *
     * Creates gauges for partition assignments and pending record counts.
     *
     * @param meterRegistry The meter registry to bind metrics to
     */
    override fun bindTo(meterRegistry: MeterRegistry) {
        Gauge
            .builder("outbox.partitions.assigned.count") {
                partitionMetricsProvider.getProcessingStats().assignedPartitions.size
            }.description("Number of partitions assigned to this instance")
            .register(meterRegistry)

        Gauge
            .builder("outbox.partitions.pending.records.total") {
                partitionMetricsProvider.getProcessingStats().totalPendingRecords
            }.description("Total number of pending records across all assigned partitions")
            .register(meterRegistry)

        Gauge
            .builder("outbox.partitions.pending.records.max") {
                val stats = partitionMetricsProvider.getProcessingStats()
                stats.pendingRecordsPerPartition.values
                    .maxOrNull()
                    ?.toDouble() ?: 0.0
            }.description("Maximum number of pending records in any assigned partition")
            .register(meterRegistry)

        Gauge
            .builder("outbox.partitions.pending.records.avg") {
                val stats = partitionMetricsProvider.getProcessingStats()
                if (stats.assignedPartitions.isEmpty()) {
                    0.0
                } else {
                    stats.pendingRecordsPerPartition.values.average()
                }
            }.description("Average number of pending records per assigned partition")
            .register(meterRegistry)

        Gauge
            .builder("outbox.cluster.instances.total") {
                partitionMetricsProvider.getPartitionStats().totalInstances
            }.description("Total number of active instances in the outbox cluster")
            .register(meterRegistry)

        Gauge
            .builder("outbox.cluster.partitions.total") {
                partitionMetricsProvider.getPartitionStats().totalPartitions
            }.description("Total number of partitions in the outbox cluster")
            .register(meterRegistry)

        Gauge
            .builder("outbox.cluster.partitions.avg_per_instance") {
                partitionMetricsProvider.getPartitionStats().averagePartitionsPerInstance
            }.description("Average number of partitions assigned per instance")
            .register(meterRegistry)

        Gauge
            .builder("outbox.cluster.partitions.unassigned.count") {
                partitionMetricsProvider.getPartitionStats().unassignedPartitionsCount.toDouble()
            }.description("Number of partitions currently unassigned (no instance owner)")
            .register(meterRegistry)

        // Per-partition unassigned flag (1 = unassigned, 0 = assigned)
        for (partition in 0 until PartitionHasher.TOTAL_PARTITIONS) {
            Gauge
                .builder("outbox.cluster.partitions.unassigned.flag") {
                    val stats = partitionMetricsProvider.getPartitionStats()
                    if (stats.unassignedPartitionNumbers.contains(partition)) 1.0 else 0.0
                }.tag("partition", partition.toString())
                .description("1 if partition is currently unassigned, else 0")
                .register(meterRegistry)
        }
    }
}
