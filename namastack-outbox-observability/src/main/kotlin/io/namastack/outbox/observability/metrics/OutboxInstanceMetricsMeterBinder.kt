package io.namastack.outbox.observability.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
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
import java.time.Clock
import java.time.Duration
import java.time.Instant

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
 * - `outbox.instance.records.oldest.age` — Age in seconds of the oldest pending record across assigned partitions
 * - `outbox.instance.records.overdue.age` — Seconds since the longest overdue record across assigned partitions became due
 *
 * **Cluster metrics:**
 * - `outbox.cluster.instances.active` — Number of active instances in the cluster
 * - `outbox.cluster.partitions.unassigned` — Partitions not assigned to any instance
 *
 * All gauges include the `outbox.channel` tag for multi-channel support.
 *
 * The age gauges report zero when no records match or no partitions are assigned, and `NaN`
 * when the value cannot be read, so a failed query is distinguishable from an empty backlog.
 * The overdue age can include records that are currently being processed.
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
    private val clock: Clock = Clock.systemDefaultZone(),
    private val stopOnFirstFailure: Boolean = true,
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
                meterRegistry.registerGauge(
                    name = OutboxMetricNames.RECORDS,
                    description = "Count of outbox records by status",
                    channelTag = channelTag,
                    tags = Tags.of(OutboxMetricKeyNames.LowCardinality.RECORD_STATUS, status.name.lowercase()),
                ) { safeGet { recordStatusRepository.countByStatus(status).toDouble() } }
            }
        }

        meterRegistry.registerGauge(
            name = OutboxMetricNames.INSTANCE_PARTITIONS_ASSIGNED,
            description = "Number of partitions assigned to this instance",
            channelTag = channelTag,
        ) { safeGet { partitionCoordinator.getAssignedPartitionNumbers().size.toDouble() } }

        meterRegistry.registerGauge(
            name = OutboxMetricNames.INSTANCE_RECORDS_PENDING,
            description = "Total pending records across all assigned partitions",
            channelTag = channelTag,
        ) { safeGet { getPendingRecordCount().toDouble() } }

        meterRegistry.registerGauge(
            name = OutboxMetricNames.INSTANCE_RECORDS_OLDEST_AGE,
            description = "Age of the oldest pending record across all assigned partitions",
            channelTag = channelTag,
            baseUnit = "seconds",
        ) { getOrNaN { getOldestRecordAgeSeconds() } }

        meterRegistry.registerGauge(
            name = OutboxMetricNames.INSTANCE_RECORDS_OVERDUE_AGE,
            description = "Time since the longest overdue record across all assigned partitions became due",
            channelTag = channelTag,
            baseUnit = "seconds",
        ) { getOrNaN { getOverdueRecordAgeSeconds() } }

        meterRegistry.registerGauge(
            name = OutboxMetricNames.CLUSTER_INSTANCES_ACTIVE,
            description = "Number of active instances in the outbox cluster",
            channelTag = channelTag,
        ) { safeGet { instanceRegistry.getActiveInstanceCount().toDouble() } }

        meterRegistry.registerGauge(
            name = OutboxMetricNames.CLUSTER_PARTITIONS_UNASSIGNED,
            description = "Number of partitions currently not assigned to any instance",
            channelTag = channelTag,
        ) {
            safeGet {
                partitionCoordinator
                    .getPartitionContext()
                    .getPartitionStats()
                    .unassignedPartitionsCount
                    .toDouble()
            }
        }
    }

    /**
     * Registers a gauge tagged with the outbox channel.
     */
    private fun MeterRegistry.registerGauge(
        name: String,
        description: String,
        channelTag: String,
        tags: Tags = Tags.empty(),
        baseUnit: String? = null,
        value: () -> Double,
    ) {
        Gauge
            .builder(name, value)
            .description(description)
            .baseUnit(baseUnit)
            .tags(tags)
            .tag(OutboxMetricKeyNames.LowCardinality.CHANNEL, channelTag)
            .register(this)
    }

    /**
     * Counts pending ([NEW]) records across the currently assigned partitions with one batch repository call.
     *
     * Includes records awaiting a future retry. Reads the current assignments on every invocation
     * and skips the repository call when no partitions are assigned.
     *
     * @return Total pending records, or zero if no partitions are assigned or no records match
     */
    private fun getPendingRecordCount(): Long {
        val partitions = partitionCoordinator.getAssignedPartitionNumbers()
        if (partitions.isEmpty()) return 0L

        return recordRepository.countRecordsByPartitions(partitions, NEW)
    }

    /**
     * Computes the age of the oldest pending ([NEW]) record across the currently assigned partitions,
     * including records awaiting a future retry.
     *
     * @return Age in seconds, or zero if no partitions are assigned or no records match
     */
    private fun getOldestRecordAgeSeconds(): Double {
        val partitions = partitionCoordinator.getAssignedPartitionNumbers()
        if (partitions.isEmpty()) return 0.0

        val now = clock.instant()
        return secondsSince(recordRepository.findOldestCreatedAtByPartitions(partitions, NEW), now)
    }

    /**
     * Computes how long the longest overdue pending ([NEW]) record across the currently assigned
     * partitions has been due, using the same eligibility rules as the poller.
     *
     * @return Age in seconds, or zero if no partitions are assigned or no records are overdue
     */
    private fun getOverdueRecordAgeSeconds(): Double {
        val partitions = partitionCoordinator.getAssignedPartitionNumbers()
        if (partitions.isEmpty()) return 0.0

        val now = clock.instant()
        val oldestDueAt =
            recordRepository.findOldestDueNextRetryAtByPartitions(
                partitions = partitions,
                status = NEW,
                now = now,
                ignoreRecordKeysWithPreviousFailure = stopOnFirstFailure,
            )
        return secondsSince(oldestDueAt, now)
    }

    /**
     * Returns the seconds between [since] and [now], or zero if [since] is `null` or after [now].
     */
    private fun secondsSince(
        since: Instant?,
        now: Instant,
    ): Double {
        if (since == null) return 0.0

        return Duration.between(since, now).toMillis().coerceAtLeast(0L) / 1000.0
    }

    /**
     * Executes the given supplier and returns its value, or `NaN` if an exception occurs.
     *
     * Unlike [safeGet], a failure stays distinguishable from a zero value.
     *
     * @param supplier the function to execute
     * @return the value from the supplier, or `NaN` if an exception is thrown
     */
    private fun getOrNaN(supplier: () -> Double): Double =
        try {
            supplier()
        } catch (ex: Exception) {
            log.trace("Failed to read metric value", ex)
            Double.NaN
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
