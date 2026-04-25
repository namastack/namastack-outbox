package io.namastack.outbox.partition

import io.namastack.outbox.OpenForProxy
import io.namastack.outbox.OutboxProperties
import io.namastack.outbox.instance.OutboxInstanceRegistry
import io.namastack.outbox.partition.PartitionHasher.TOTAL_PARTITIONS
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.scheduling.annotation.Scheduled
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Central orchestrator for partition ownership management.
 *
 * Responsibilities:
 *  - Bootstrap: claim all partitions if none exist yet.
 *  - Rebalance: reclaim stale partitions and release surplus when topology changes.
 *  - Caching: memorizes owned partition numbers until next rebalance.
 *  - Stats: provides record key partition distribution metrics.
 *
 * Concurrency assumptions:
 *  - Rebalance invoked after batch completion (scheduler guarantees no overlapping processing).
 *  - Ownership changes only through this coordinator / repository layer.
 *
 * @author Roland Beisel
 * @since 0.2.0
 */
@OpenForProxy
class PartitionCoordinator(
    private val instanceRegistry: OutboxInstanceRegistry,
    private val partitionAssignmentRepository: PartitionAssignmentRepository,
    private val partitionAssignmentCache: PartitionAssignmentCache,
    private val partitionDrainTracker: PartitionDrainTracker = PartitionDrainTracker(),
    private val properties: OutboxProperties = OutboxProperties(),
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(PartitionCoordinator::class.java)
    private val currentInstanceId by lazy { instanceRegistry.getCurrentInstanceId() }
    private val leaseDuration = Duration.ofSeconds(properties.partition.leaseDurationSeconds)

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
    @Scheduled(
        initialDelayString = "0",
        fixedDelayString =
            $$"${namastack.outbox.rebalance-interval:${namastack.outbox.instance.rebalance-interval:10000}}",
        scheduler = "outboxDefaultScheduler",
    )
    fun rebalance() {
        log.debug("Starting rebalance for instance {}", currentInstanceId)

        try {
            val partitionContext = getPartitionContext()
            if (partitionContext.hasNoPartitionAssignments()) {
                bootstrapPartitions()
                return
            }

            renewOwnedLeases(partitionContext)
            claimStalePartitions(partitionContext)
            startDrainingSurplusPartitions(partitionContext)
            releaseSurplusPartitions(partitionContext)
        } finally {
            partitionAssignmentCache.evictAll()
        }
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
            now = Instant.now(clock),
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
            log.error("Failed to bootstrap partitions for instance {}: {}", currentInstanceId, e.message)
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
                    leaseDuration = leaseDuration,
                    version = null,
                )
            }.toSet()

    /**
     * Claims stale partitions that are currently owned by inactive instances.
     * Only partitions that are not assigned to any active instance are considered.
     * Updates ownership to the current instance.
     */
    private fun renewOwnedLeases(partitionContext: PartitionContext) {
        val ownedAssignments = partitionContext.getOwnedPartitionAssignments()
        if (ownedAssignments.isEmpty()) return

        ownedAssignments.forEach { partitionAssignment ->
            partitionAssignment.renewLease(currentInstanceId, clock, leaseDuration)
        }

        try {
            partitionAssignmentRepository.saveAll(ownedAssignments)
        } catch (_: Exception) {
            log.debug("Could not renew partition leases for instance {}", currentInstanceId)
        }
    }

    private fun claimStalePartitions(partitionContext: PartitionContext) {
        val partitionsToClaim = partitionContext.getPartitionAssignmentsToClaim()
        if (partitionsToClaim.isEmpty()) return

        partitionsToClaim.forEach { partitionAssignment ->
            partitionAssignment.claim(currentInstanceId, clock, leaseDuration)
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
     * Marks surplus partitions as draining so the owner stops taking new work.
     */
    private fun startDrainingSurplusPartitions(partitionContext: PartitionContext) {
        val partitionsToDrain = partitionContext.getPartitionAssignmentsToStartDraining()
        if (partitionsToDrain.isEmpty()) return

        partitionsToDrain.forEach { partitionAssignment ->
            partitionAssignment.markDraining(currentInstanceId, clock, leaseDuration)
        }

        val partitionNumbersToDrain = partitionsToDrain.map { it.partitionNumber }

        try {
            partitionAssignmentRepository.saveAll(partitionsToDrain)
        } catch (_: Exception) {
            log.debug(
                "Could not mark partitions {} as draining for instance {}",
                partitionNumbersToDrain,
                currentInstanceId,
            )
            return
        }

        log.debug(
            "Successfully marked {} partitions as draining for instance {}: {}",
            partitionsToDrain.size,
            currentInstanceId,
            partitionNumbersToDrain,
        )
    }

    /**
     * Releases draining partitions once the current instance has no local work in flight for them.
     */
    private fun releaseSurplusPartitions(partitionContext: PartitionContext) {
        val releaseCandidates = partitionContext.getPartitionAssignmentsToRelease()
        if (releaseCandidates.isEmpty()) return

        val partitionsToRelease =
            releaseCandidates.filterNot { assignment ->
                partitionDrainTracker.hasInFlightRecords(setOf(assignment.partitionNumber))
            }.toSet()
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
