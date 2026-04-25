package io.namastack.outbox.partition

import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Model representing a partition assignment.
 *
 * Partitions are work units that are distributed across multiple instances.
 * Each partition is assigned to exactly one instance and can be claimed
 * or reassigned based on instance availability.
 *
 * @param partitionNumber The unique partition identifier (0 to TOTAL_PARTITIONS-1)
 * @param instanceId The ID of the instance currently owning this partition
 *
 * @author Roland Beisel
 * @since 0.4.0
 */
data class PartitionAssignment(
    val partitionNumber: Int,
    var instanceId: String?,
    var updatedAt: Instant,
    var leaseExpiresAt: Instant? = null,
    var draining: Boolean = false,
    val version: Long? = null,
) {
    companion object {
        fun create(
            partitionNumber: Int,
            instanceId: String,
            clock: Clock,
            version: Long?,
        ): PartitionAssignment = create(partitionNumber, instanceId, clock, Duration.ofSeconds(30), version)

        /**
         * Creates a new partition assignment.
         *
         * @param partitionNumber The partition identifier
         * @param instanceId The instance ID claiming this partition
         * @param clock Clock for timestamp generation
         * @return A new OutboxPartition instance
         */
        fun create(
            partitionNumber: Int,
            instanceId: String,
            clock: Clock,
            leaseDuration: Duration,
            version: Long?,
        ): PartitionAssignment {
            val now = Instant.now(clock)
            return PartitionAssignment(
                partitionNumber = partitionNumber,
                instanceId = instanceId,
                updatedAt = now,
                leaseExpiresAt = now.plus(leaseDuration),
                draining = false,
                version = version,
            )
        }
    }

    /**
     * Claims this partition for the given instance.
     * Updates the instanceId and timestamp.
     *
     * @param instanceId The instance ID claiming this partition
     * @param clock Clock for timestamp generation
     */
    fun claim(
        instanceId: String,
        clock: Clock,
        leaseDuration: Duration = Duration.ofSeconds(30),
    ) {
        val now = Instant.now(clock)

        this.instanceId = instanceId
        this.updatedAt = now
        this.leaseExpiresAt = now.plus(leaseDuration)
        this.draining = false
    }

    /**
     * Extends this lease while retaining ownership.
     */
    fun renewLease(
        currentInstanceId: String,
        clock: Clock,
        leaseDuration: Duration,
    ) {
        ensureOwnedBy(currentInstanceId)

        val now = Instant.now(clock)
        this.updatedAt = now
        this.leaseExpiresAt = now.plus(leaseDuration)
    }

    /**
     * Marks the partition as draining so the owner stops taking new work for it.
     */
    fun markDraining(
        currentInstanceId: String,
        clock: Clock,
        leaseDuration: Duration,
    ) {
        ensureOwnedBy(currentInstanceId)

        val now = Instant.now(clock)
        this.updatedAt = now
        this.leaseExpiresAt = now.plus(leaseDuration)
        this.draining = true
    }

    /**
     * Releases this partition if owned by the current instance.
     * Sets instanceId to null and updates the timestamp.
     *
     * @param currentInstanceId The instance ID that should own this partition
     * @param clock Clock for timestamp generation
     * @return true if release was successful, false if partition is owned by a different instance
     */
    fun release(
        currentInstanceId: String,
        clock: Clock,
    ) {
        ensureOwnedBy(currentInstanceId)

        val now = Instant.now(clock)

        this.instanceId = null
        this.updatedAt = now
        this.leaseExpiresAt = null
        this.draining = false
    }

    fun isLeaseExpired(now: Instant): Boolean = leaseExpiresAt?.let { !it.isAfter(now) } ?: false

    fun isClaimable(now: Instant): Boolean = instanceId == null || isLeaseExpired(now)

    fun isProcessable(now: Instant): Boolean = instanceId != null && !draining && !isLeaseExpired(now)

    private fun ensureOwnedBy(currentInstanceId: String) {
        if (instanceId != currentInstanceId) {
            throw IllegalStateException(
                "Could not update partition assignment with partition number $partitionNumber. Instance $currentInstanceId does not own this partition assignment.",
            )
        }
    }
}
