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

        collector.recordCounts.forEach { (status, reading) ->
            Gauge
                .builder(OutboxMetricNames.RECORDS, reading::value)
                .description("Count of outbox records by status")
                .tag(OutboxMetricKeyNames.LowCardinality.RECORD_STATUS, status.name.lowercase())
                .tag(OutboxMetricKeyNames.LowCardinality.CHANNEL, channelTag)
                .register(meterRegistry)
        }

        register(
            registry = meterRegistry,
            name = OutboxMetricNames.INSTANCE_PARTITIONS_ASSIGNED,
            description = "Number of partitions assigned to this instance",
        ) {
            collector.assignedPartitions.value()
        }
        register(
            registry = meterRegistry,
            name = OutboxMetricNames.INSTANCE_RECORDS_PENDING,
            description = "Total pending records across all assigned partitions",
        ) {
            collector.pendingRecords.value()
        }
        register(
            registry = meterRegistry,
            name = OutboxMetricNames.CLUSTER_INSTANCES_ACTIVE,
            description = "Number of active instances in the outbox cluster",
        ) {
            collector.activeInstances.value()
        }
        register(
            registry = meterRegistry,
            name = OutboxMetricNames.CLUSTER_PARTITIONS_UNASSIGNED,
            description = "Number of partitions currently not assigned to any instance",
        ) {
            collector.unassignedPartitions.value()
        }
        register(
            registry = meterRegistry,
            name = OutboxMetricNames.METRICS_COLLECTION_LAST_SUCCESS_TIMESTAMP,
            description = "Unix timestamp in seconds of the last successful collection of all outbox gauges",
        ) {
            collector.lastSuccessTimestamp
        }
    }

    private fun register(
        registry: MeterRegistry,
        name: String,
        description: String,
        value: () -> Double,
    ) {
        Gauge
            .builder(name, value)
            .description(description)
            .tag(OutboxMetricKeyNames.LowCardinality.CHANNEL, channelNameProvider.getChannelName())
            .register(registry)
    }
}
