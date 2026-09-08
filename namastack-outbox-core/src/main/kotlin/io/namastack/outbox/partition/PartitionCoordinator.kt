package io.namastack.outbox.partition

import io.micrometer.observation.ObservationRegistry
import io.namastack.outbox.OpenForProxy
import io.namastack.outbox.OutboxProcessingScheduler
import io.namastack.outbox.instance.OutboxInstanceRegistry
import io.namastack.outbox.partition.PartitionHasher.TOTAL_PARTITIONS
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.support.ScheduledMethodRunnable
import java.lang.reflect.Method
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Central orchestrator for partition ownership management.
 *
 * Responsibilities:
 *  - Bootstrap: claim all partitions if none exist yet.
 *  - Rebalance: reclaim stale partitions and release surplus when topology changes.
 *  - Caching: memorizes owned partition numbers until next rebalance.
 *  - Stats: provides record key partition distribution metrics.
 *
 * Rebalancing owns its scheduling lifecycle. Record processing uses [withStableAssignments] so a
 * rebalance cannot overlap a processing batch even when the supplied scheduler has multiple threads.
 *
 * @author Roland Beisel
 * @since 0.2.0
 */
@OpenForProxy
class PartitionCoordinator(
    private val instanceRegistry: OutboxInstanceRegistry,
    private val partitionAssignmentRepository: PartitionAssignmentRepository,
    private val partitionAssignmentCache: PartitionAssignmentCache,
    private val clock: Clock,
    private val taskScheduler: TaskScheduler,
    private val rebalanceInterval: Duration,
    private val observationRegistry: () -> ObservationRegistry,
) : SmartLifecycle {
    companion object {
        private val REBALANCE_METHOD: Method = PartitionCoordinator::class.java.getMethod("rebalance")
    }

    private val log = LoggerFactory.getLogger(PartitionCoordinator::class.java)
    private val currentInstanceId by lazy { instanceRegistry.getCurrentInstanceId() }
    private val assignmentLock = ReentrantLock()

    @Volatile
    private var running = false

    private var scheduledRebalance: ScheduledFuture<*>? = null

    /** Starts after instance registration and before record processing. */
    override fun getPhase(): Int = 1

    override fun isRunning(): Boolean = running

    /** Performs the initial rebalance and then schedules periodic executions. */
    @Synchronized
    override fun start() {
        if (running) return

        rebalance()
        val observedRebalance =
            ScheduledMethodRunnable(
                this,
                REBALANCE_METHOD,
                OutboxProcessingScheduler.SCHEDULER_NAME,
                observationRegistry,
            )
        val runnable =
            Runnable {
                assignmentLock.withLock {
                    if (running) observedRebalance.run()
                }
            }
        val firstExecution = taskScheduler.clock.instant().plus(rebalanceInterval)
        running = true
        try {
            scheduledRebalance =
                checkNotNull(taskScheduler.scheduleWithFixedDelay(runnable, firstExecution, rebalanceInterval)) {
                    "TaskScheduler did not schedule partition rebalancing"
                }
        } catch (failure: Throwable) {
            assignmentLock.withLock { running = false }
            throw failure
        }
    }

    /** Cancels future executions and waits for an active rebalance to leave its critical section. */
    @Synchronized
    override fun stop() {
        if (!running) return

        scheduledRebalance?.cancel(false)
        scheduledRebalance = null
        assignmentLock.withLock { running = false }
    }

    /**
     * Return currently owned partition numbers (cached until next rebalance).
     * Cache is invalidated after each successful rebalance.
     */
    fun getAssignedPartitionNumbers(): Set<Int> =
        partitionAssignmentCache.getAssignedPartitionNumbers(currentInstanceId)

    /**
     * Perform a rebalance cycle:
     *  1. Fetch active instance IDs.
     *  2. Build immutable context snapshot.
     *  3. Bootstrap if no assignments exist.
     *  4. Claim stale partitions, then release surplus for new instances.
     *  5. Invalidate cached partition list.
     */
    fun rebalance(): Unit =
        assignmentLock.withLock {
            log.debug("Starting rebalance for instance {}", currentInstanceId)

            try {
                val partitionContext = getPartitionContext()
                if (partitionContext.hasNoPartitionAssignments()) {
                    bootstrapPartitions()
                    return
                }

                claimStalePartitions(partitionContext)
                releaseSurplusPartitions(partitionContext)
            } finally {
                partitionAssignmentCache.evictAll()
            }
        }

    /** Runs work against one stable partition assignment without overlapping a rebalance. */
    internal fun <T> withStableAssignments(action: (Set<Int>) -> T): T =
        assignmentLock.withLock {
            action(getAssignedPartitionNumbers())
        }

    /**
     * Returns a snapshot of the current partition context, including active instance IDs and all partition assignments.
     * Returns null if no active instances are found.
     */
    fun getPartitionContext(): PartitionContext {
        val activeInstanceIds = instanceRegistry.getActiveInstanceIds()
        if (activeInstanceIds.isEmpty()) {
            throw IllegalStateException("No active instance ids found")
        }

        val partitionAssignments = partitionAssignmentRepository.findAll()

        return PartitionContext(
            currentInstanceId = currentInstanceId,
            activeInstanceIds = activeInstanceIds,
            partitionAssignments = partitionAssignments,
        )
    }

    /**
     * Attempts to claim all partitions for this instance during initial startup.
     * If another instance bootstraps concurrently, failures are silently ignored.
     *
     * Note: If a DataIntegrityViolationException occurs (e.g. due to unique constraint violation),
     * this is expected behavior and will be handled silently. However, Hibernate's SqlExceptionHelper
     * will log an error statement in this case, which cannot be suppressed.
     */
    private fun bootstrapPartitions() {
        val partitionAssignments = createInitialPartitionAssignments()

        try {
            partitionAssignmentRepository.saveAll(partitionAssignments)
        } catch (_: DataIntegrityViolationException) {
            log.debug(
                "Another instance initialized partitions, skipping bootstrap for {}",
                currentInstanceId,
            )
            return
        } catch (e: Exception) {
            log.error("Failed to bootstrap partitions for instance {}: {}", currentInstanceId, e.message, e)
            return
        }

        log.debug(
            "Successfully bootstrapped all {} partitions for instance {}",
            TOTAL_PARTITIONS,
            currentInstanceId,
        )
    }

    /**
     * Creates initial partition assignments for all partitions assigned to this instance.
     */
    private fun createInitialPartitionAssignments(): Set<PartitionAssignment> =
        (0 until TOTAL_PARTITIONS)
            .map { partitionNumber ->
                PartitionAssignment.create(
                    partitionNumber = partitionNumber,
                    instanceId = currentInstanceId,
                    clock = clock,
                    version = null,
                )
            }.toSet()

    /**
     * Claims stale partitions that are currently owned by inactive instances.
     * Only partitions that are not assigned to any active instance are considered.
     * Updates ownership to the current instance.
     */
    private fun claimStalePartitions(partitionContext: PartitionContext) {
        val partitionsToClaim = partitionContext.getPartitionAssignmentsToClaim()
        if (partitionsToClaim.isEmpty()) return

        partitionsToClaim.forEach { partitionAssignment ->
            partitionAssignment.claim(currentInstanceId, clock)
        }

        val partitionNumbersToClaim = partitionsToClaim.map { it.partitionNumber }

        try {
            partitionAssignmentRepository.saveAll(partitionsToClaim)
        } catch (_: Exception) {
            log.debug(
                "Could not claim {} partitions for instance {}: {}",
                partitionsToClaim.size,
                currentInstanceId,
                partitionNumbersToClaim,
            )
            return
        }

        log.debug(
            "Successfully claimed {} partitions for instance {}: {}",
            partitionsToClaim.size,
            currentInstanceId,
            partitionNumbersToClaim,
        )
    }

    /**
     * Releases surplus partitions owned by the current instance to achieve a balanced distribution.
     * Only partitions assigned to this instance are considered for release.
     */
    private fun releaseSurplusPartitions(partitionContext: PartitionContext) {
        val partitionsToRelease = partitionContext.getPartitionAssignmentsToRelease()
        if (partitionsToRelease.isEmpty()) return

        partitionsToRelease.forEach { partitionAssignment ->
            partitionAssignment.release(currentInstanceId, clock)
        }

        val partitionNumbersToRelease = partitionsToRelease.map { it.partitionNumber }

        try {
            partitionAssignmentRepository.saveAll(partitionsToRelease)
        } catch (_: Exception) {
            log.debug(
                "Could not release partitions {} from instance {}",
                partitionNumbersToRelease,
                currentInstanceId,
            )
            return
        }

        log.debug(
            "Successfully released {} partitions from instance {}: {}",
            partitionsToRelease.size,
            currentInstanceId,
            partitionNumbersToRelease,
        )
    }
}
