package io.namastack.outbox

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import io.namastack.outbox.observability.OutboxMeters
import io.namastack.outbox.observability.OutboxMeters.Keys
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
            .builder(OutboxMeters.PARTITIONS_ASSIGNED_COUNT.getName()) {
                partitionMetricsProvider.getProcessingStats().assignedPartitions.size
            }.description(OutboxMeters.PARTITIONS_ASSIGNED_COUNT.description)
            .register(meterRegistry)

        Gauge
            .builder(OutboxMeters.PARTITIONS_PENDING_RECORDS_TOTAL.getName()) {
                partitionMetricsProvider.getProcessingStats().totalPendingRecords
            }.description(OutboxMeters.PARTITIONS_PENDING_RECORDS_TOTAL.description)
            .register(meterRegistry)

        Gauge
            .builder(OutboxMeters.PARTITIONS_PENDING_RECORDS_MAX.getName()) {
                val stats = partitionMetricsProvider.getProcessingStats()
                stats.pendingRecordsPerPartition.values
                    .maxOrNull()
                    ?.toDouble() ?: 0.0
            }.description(OutboxMeters.PARTITIONS_PENDING_RECORDS_MAX.description)
            .register(meterRegistry)

        Gauge
            .builder(OutboxMeters.PARTITIONS_PENDING_RECORDS_AVG.getName()) {
                val stats = partitionMetricsProvider.getProcessingStats()
                if (stats.assignedPartitions.isEmpty()) {
                    0.0
                } else {
                    stats.pendingRecordsPerPartition.values.average()
                }
            }.description(OutboxMeters.PARTITIONS_PENDING_RECORDS_AVG.description)
            .register(meterRegistry)

        Gauge
            .builder(OutboxMeters.CLUSTER_INSTANCES_TOTAL.getName()) {
                partitionMetricsProvider.getPartitionStats().totalInstances
            }.description(OutboxMeters.CLUSTER_INSTANCES_TOTAL.description)
            .register(meterRegistry)

        Gauge
            .builder(OutboxMeters.CLUSTER_PARTITIONS_TOTAL.getName()) {
                partitionMetricsProvider.getPartitionStats().totalPartitions
            }.description(OutboxMeters.CLUSTER_PARTITIONS_TOTAL.description)
            .register(meterRegistry)

        Gauge
            .builder(OutboxMeters.CLUSTER_PARTITIONS_AVG_PER_INSTANCE.getName()) {
                partitionMetricsProvider.getPartitionStats().averagePartitionsPerInstance
            }.description(OutboxMeters.CLUSTER_PARTITIONS_AVG_PER_INSTANCE.description)
            .register(meterRegistry)

        Gauge
            .builder(OutboxMeters.CLUSTER_PARTITIONS_UNASSIGNED_COUNT.getName()) {
                partitionMetricsProvider.getPartitionStats().unassignedPartitionsCount.toDouble()
            }.description(OutboxMeters.CLUSTER_PARTITIONS_UNASSIGNED_COUNT.description)
            .register(meterRegistry)

        // Per-partition unassigned flag (1 = unassigned, 0 = assigned)
        for (partition in 0 until PartitionHasher.TOTAL_PARTITIONS) {
            Gauge
                .builder(OutboxMeters.CLUSTER_PARTITIONS_UNASSIGNED_FLAG.getName()) {
                    val stats = partitionMetricsProvider.getPartitionStats()
                    if (stats.unassignedPartitionNumbers.contains(partition)) 1.0 else 0.0
                }.tag(Keys.Partition.asString(), partition.toString())
                .description(OutboxMeters.CLUSTER_PARTITIONS_UNASSIGNED_FLAG.description)
                .register(meterRegistry)
        }
    }
}
