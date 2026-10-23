package io.namastack.outbox.observability.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import io.namastack.outbox.OutboxChannelNameProvider
import io.namastack.outbox.observability.OutboxMetricKeyNames
import io.namastack.outbox.observability.OutboxMetricNames

/**
 * Registers record, instance, and cluster gauges backed exclusively by background readings.
 * Unknown, failed, or expired state readings return NaN. The last-success timestamp remains
 * available after a failed collection and does not expire. All gauges include the outbox channel tag.
 *
 * Age gauges report zero when no records match or no partitions are assigned.
 * The overdue age can include records currently being processed.
 *
 * This binder is runtime infrastructure, not an application extension point.
 *
 * @author Roland Beisel
 * @since 1.7.0
 */
class OutboxInstanceMetricsMeterBinder internal constructor(
    private val collector: OutboxMetricsCollector,
    private val channelNameProvider: OutboxChannelNameProvider,
) : MeterBinder {
    override fun bindTo(meterRegistry: MeterRegistry) {
        val channelTag = channelNameProvider.getChannelName()

        collector.recordStatuses.forEach { status ->
            Gauge
                .builder(OutboxMetricNames.RECORDS) { collector.recordCount(status) }
                .description("Count of outbox records by status")
                .tag(OutboxMetricKeyNames.LowCardinality.RECORD_STATUS, status.name.lowercase())
                .tag(OutboxMetricKeyNames.LowCardinality.CHANNEL, channelTag)
                .register(meterRegistry)
        }

        Gauge
            .builder(OutboxMetricNames.INSTANCE_PARTITIONS_ASSIGNED, collector::assignedPartitions)
            .description("Number of partitions assigned to this instance")
            .tag(OutboxMetricKeyNames.LowCardinality.CHANNEL, channelTag)
            .register(meterRegistry)
        Gauge
            .builder(OutboxMetricNames.INSTANCE_RECORDS_PENDING, collector::pendingRecords)
            .description("Total pending records across all assigned partitions")
            .tag(OutboxMetricKeyNames.LowCardinality.CHANNEL, channelTag)
            .register(meterRegistry)
        Gauge
            .builder(OutboxMetricNames.INSTANCE_RECORDS_OLDEST_AGE, collector::oldestRecordAge)
            .description("Age of the oldest pending record across all assigned partitions")
            .baseUnit("seconds")
            .tag(OutboxMetricKeyNames.LowCardinality.CHANNEL, channelTag)
            .register(meterRegistry)
        Gauge
            .builder(OutboxMetricNames.INSTANCE_RECORDS_OVERDUE_AGE, collector::overdueRecordAge)
            .description("Time since the longest overdue record across all assigned partitions became due")
            .baseUnit("seconds")
            .tag(OutboxMetricKeyNames.LowCardinality.CHANNEL, channelTag)
            .register(meterRegistry)
        Gauge
            .builder(OutboxMetricNames.CLUSTER_INSTANCES_ACTIVE, collector::activeInstances)
            .description("Number of active instances in the outbox cluster")
            .tag(OutboxMetricKeyNames.LowCardinality.CHANNEL, channelTag)
            .register(meterRegistry)
        Gauge
            .builder(OutboxMetricNames.CLUSTER_PARTITIONS_UNASSIGNED, collector::unassignedPartitions)
            .description("Number of partitions currently not assigned to any instance")
            .tag(OutboxMetricKeyNames.LowCardinality.CHANNEL, channelTag)
            .register(meterRegistry)
        Gauge
            .builder(OutboxMetricNames.METRICS_COLLECTION_LAST_SUCCESS_TIMESTAMP) { collector.lastSuccessTimestamp }
            .description("Unix timestamp in seconds of the last successful collection of all outbox gauges")
            .baseUnit("seconds")
            .tag(OutboxMetricKeyNames.LowCardinality.CHANNEL, channelTag)
            .register(meterRegistry)

        collector.markBound()
    }
}
