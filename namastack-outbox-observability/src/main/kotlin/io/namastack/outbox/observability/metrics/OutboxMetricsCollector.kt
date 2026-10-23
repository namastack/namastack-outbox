package io.namastack.outbox.observability.metrics

import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.OutboxRecordStatus
import io.namastack.outbox.OutboxRecordStatus.NEW
import io.namastack.outbox.OutboxRecordStatusRepository
import io.namastack.outbox.instance.OutboxInstanceRegistry
import io.namastack.outbox.partition.PartitionCoordinator
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.scheduling.TaskScheduler
import java.time.Clock
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Collects record, instance, and partition metrics independently of meter observation.
 *
 * Uses a dedicated scheduler to keep collection off metric scrape threads and the processing and
 * heartbeat schedulers. Collection still shares database connections and partition-cache resources
 * with the outbox runtime, so resource contention can affect processing.
 * Each metric is updated independently, retaining fresh readings when another query fails.
 *
 * @param recordRepository Repository for pending record counts
 * @param recordStatusRepository Optional repository for record counts by status
 * @param partitionCoordinator Coordinator for partition assignments and cluster state
 * @param instanceRegistry Registry for active instance counts
 * @param properties Background collection interval and maximum reading age
 * @param clock Clock for reading timestamps and expiry
 * @param taskScheduler Dedicated scheduler for background collection
 *
 * @author Roland Beisel
 * @since 1.12.0
 */
internal class OutboxMetricsCollector(
    private val recordRepository: OutboxRecordRepository,
    private val recordStatusRepository: OutboxRecordStatusRepository?,
    private val partitionCoordinator: PartitionCoordinator,
    private val instanceRegistry: OutboxInstanceRegistry,
    private val properties: OutboxMetricsProperties,
    private val clock: Clock,
    private val taskScheduler: TaskScheduler,
) : SmartLifecycle {
    companion object {
        const val SCHEDULER_NAME: String = "outboxMetricsScheduler"
    }

    private val log = LoggerFactory.getLogger(OutboxMetricsCollector::class.java)
    private val collecting = AtomicBoolean(false)

    val recordCounts: Map<OutboxRecordStatus, OutboxMetricValue> = createRecordCounts()
    val assignedPartitions = OutboxMetricValue(properties.maxAge, clock)
    val pendingRecords = OutboxMetricValue(properties.maxAge, clock)
    val activeInstances = OutboxMetricValue(properties.maxAge, clock)
    val unassignedPartitions = OutboxMetricValue(properties.maxAge, clock)

    @Volatile
    var lastSuccessTimestamp: Double = Double.NaN
        private set

    @Volatile
    private var scheduledTask: ScheduledFuture<*>? = null

    /** Starts after instance registration and partition coordination, and stops before them. */
    override fun getPhase(): Int = Int.MAX_VALUE

    override fun isRunning(): Boolean = scheduledTask != null

    /** Registers background collection once, without waiting for a database query. */
    @Synchronized
    override fun start() {
        if (isRunning) return

        scheduledTask = taskScheduler.scheduleWithFixedDelay(::refresh, properties.refreshInterval)
        log.debug("Outbox metrics collection scheduled (interval={})", properties.refreshInterval)
    }

    /** Cancels collection and interrupts an active query without waiting for it to finish. */
    @Synchronized
    override fun stop() {
        scheduledTask?.cancel(true)
        scheduledTask = null
        log.debug("Outbox metrics collection stopped")
    }

    /**
     * Refreshes all metrics, continuing with independent queries when a measurement fails.
     * The success timestamp advances only when every measurement succeeds.
     * Concurrent refreshes are skipped without waiting for the active collection.
     */
    internal fun refresh() {
        if (!collecting.compareAndSet(false, true)) return

        try {
            val recordsCollected = collectRecordCounts()
            val partitionsCollected = collectPartitionMetrics()
            val instancesCollected = collectActiveInstances()
            val unassignedCollected = collectUnassignedPartitions()

            if (recordsCollected && partitionsCollected && instancesCollected && unassignedCollected) {
                lastSuccessTimestamp = clock.instant().epochSecond.toDouble()
            }
        } finally {
            collecting.set(false)
        }
    }

    private fun createRecordCounts(): Map<OutboxRecordStatus, OutboxMetricValue> {
        if (recordStatusRepository == null) return emptyMap()

        return OutboxRecordStatus.entries.associateWith { OutboxMetricValue(properties.maxAge, clock) }
    }

    private fun collectRecordCounts(): Boolean {
        val repository = recordStatusRepository ?: return true
        var successful = true

        recordCounts.forEach { (status, metric) ->
            val collected = collectMetric(metric) { repository.countByStatus(status).toDouble() }
            if (!collected) successful = false
        }

        return successful
    }

    private fun collectPartitionMetrics(): Boolean {
        if (Thread.currentThread().isInterrupted) return false

        val partitions =
            try {
                partitionCoordinator.getAssignedPartitionNumbers()
            } catch (ex: Exception) {
                assignedPartitions.invalidate()
                pendingRecords.invalidate()
                handleCollectionFailure(ex)
                return false
            }

        assignedPartitions.update(partitions.size.toDouble())

        return collectMetric(pendingRecords) {
            if (partitions.isEmpty()) 0.0 else recordRepository.countRecordsByPartitions(partitions, NEW).toDouble()
        }
    }

    private fun collectActiveInstances(): Boolean =
        collectMetric(activeInstances) {
            instanceRegistry.getActiveInstanceCount().toDouble()
        }

    private fun collectUnassignedPartitions(): Boolean =
        collectMetric(unassignedPartitions) {
            partitionCoordinator
                .getPartitionContext()
                .getPartitionStats()
                .unassignedPartitionsCount
                .toDouble()
        }

    private fun collectMetric(
        metric: OutboxMetricValue,
        supplier: () -> Double,
    ): Boolean {
        if (Thread.currentThread().isInterrupted) return false

        return try {
            metric.update(supplier())
            true
        } catch (ex: Exception) {
            metric.invalidate()
            handleCollectionFailure(ex)
            false
        }
    }

    private fun handleCollectionFailure(ex: Exception) {
        if (ex is InterruptedException) Thread.currentThread().interrupt()

        log.debug("Failed to collect outbox metric", ex)
    }
}
