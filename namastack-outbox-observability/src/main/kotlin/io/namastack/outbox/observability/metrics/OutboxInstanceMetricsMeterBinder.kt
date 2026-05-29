package io.namastack.outbox.observability.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import io.namastack.outbox.OutboxChannelNameProvider
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.OutboxRecordStatus
import io.namastack.outbox.OutboxRecordStatus.NEW
import io.namastack.outbox.OutboxRecordStatusRepository
import io.namastack.outbox.instance.OutboxInstanceRegistry
import io.namastack.outbox.observability.OutboxMetricKeyNames
import io.namastack.outbox.observability.OutboxMetricNames
import io.namastack.outbox.partition.PartitionCoordinator
import org.slf4j.LoggerFactory

/**
 * Micrometer [MeterBinder] that registers instance-level and cluster-level gauges
 * for monitoring the outbox processing infrastructure.
 *
 * These are state-based gauges (polled on scrape) that complement the Observation-based
 * timer metrics. Together they provide full operational visibility.
 *
 * **Record metrics:**
 * - `outbox.records{outbox.record.status=new|failed|completed}` — Record counts by status
 *
 * **Instance metrics:**
 * - `outbox.instance.partitions.assigned` — Number of partitions assigned to this instance
 * - `outbox.instance.records.pending` — Total pending records across assigned partitions
 *
 * **Cluster metrics:**
 * - `outbox.cluster.instances.active` — Number of active instances in the cluster
 * - `outbox.cluster.partitions.unassigned` — Partitions not assigned to any instance
 *
 * All gauges include the `outbox.channel` tag for multi-channel support.
 *
 * @author Roland Beisel
 * @since 1.7.0
 */
class OutboxInstanceMetricsMeterBinder(
    private val recordRepository: OutboxRecordRepository,
    private val recordStatusRepository: OutboxRecordStatusRepository?,
    private val partitionCoordinator: PartitionCoordinator,
    private val instanceRegistry: OutboxInstanceRegistry,
    private val channelNameProvider: OutboxChannelNameProvider,
) : MeterBinder {
    private val log = LoggerFactory.getLogger(OutboxInstanceMetricsMeterBinder::class.java)

    /**
     * Register all instance-level and cluster-level gauges for the outbox metrics.
     *
     * This method is called by Micrometer to bind the defined gauges to the provided [MeterRegistry].
     * It registers gauges for record counts by status, partition assignments, pending records,
     * and cluster-wide statistics, all tagged with the outbox channel.
     *
     * @param meterRegistry the [MeterRegistry] to register metrics with
     */
    override fun bindTo(meterRegistry: MeterRegistry) {
        val channelTag = channelNameProvider.getChannelName()

        if (recordStatusRepository != null) {
            OutboxRecordStatus.entries.forEach { status ->
                Gauge
                    .builder(OutboxMetricNames.RECORDS) {
                        safeGet { recordStatusRepository.countByStatus(status).toDouble() }
                    }.description("Count of outbox records by status")
                    .tag(OutboxMetricKeyNames.LowCardinality.RECORD_STATUS, status.name.lowercase())
                    .tag(OutboxMetricKeyNames.LowCardinality.CHANNEL, channelTag)
                    .register(meterRegistry)
            }
        }

        Gauge
            .builder(OutboxMetricNames.INSTANCE_PARTITIONS_ASSIGNED) {
                safeGet { partitionCoordinator.getAssignedPartitionNumbers().size.toDouble() }
            }.description("Number of partitions assigned to this instance")
            .tag(OutboxMetricKeyNames.LowCardinality.CHANNEL, channelTag)
            .register(meterRegistry)

        Gauge
            .builder(OutboxMetricNames.INSTANCE_RECORDS_PENDING) {
                safeGet { getPendingRecordsPerPartition().values.sum().toDouble() }
            }.description("Total pending records across all assigned partitions")
            .tag(OutboxMetricKeyNames.LowCardinality.CHANNEL, channelTag)
            .register(meterRegistry)

        Gauge
            .builder(OutboxMetricNames.CLUSTER_INSTANCES_ACTIVE) {
                safeGet { instanceRegistry.getActiveInstanceCount().toDouble() }
            }.description("Number of active instances in the outbox cluster")
            .tag(OutboxMetricKeyNames.LowCardinality.CHANNEL, channelTag)
            .register(meterRegistry)

        Gauge
            .builder(OutboxMetricNames.CLUSTER_PARTITIONS_UNASSIGNED) {
                safeGet {
                    partitionCoordinator
                        .getPartitionContext()
                        .getPartitionStats()
                        .unassignedPartitionsCount
                        .toDouble()
                }
            }.description("Number of partitions currently not assigned to any instance")
            .tag(OutboxMetricKeyNames.LowCardinality.CHANNEL, channelTag)
            .register(meterRegistry)
    }

    /**
     * Returns a map of assigned partition numbers to the count of pending (NEW) records in each partition.
     *
     * @return a map where the key is the partition number and the value is the count of pending records
     */
    private fun getPendingRecordsPerPartition(): Map<Int, Long> {
        val partitions = partitionCoordinator.getAssignedPartitionNumbers()
        return partitions.associateWith { partition ->
            recordRepository.countRecordsByPartition(partition, NEW)
        }
    }

    /**
     * Executes the given supplier and returns its value, or 0.0 if an exception occurs.
     *
     * This is used to safely read metric values without failing the gauge registration.
     *
     * @param supplier the function to execute
     * @return the value from the supplier, or 0.0 if an exception is thrown
     */
    private fun safeGet(supplier: () -> Double): Double =
        try {
            supplier()
        } catch (ex: Exception) {
            log.trace("Failed to read metric value", ex)
            0.0
        }
}
