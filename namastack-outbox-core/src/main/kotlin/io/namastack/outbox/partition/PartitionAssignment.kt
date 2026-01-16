package io.namastack.outbox.partition

import java.time.Clock
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
    val version: Long? = null,
) {
    companion object {
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
            version: Long?,
        ): PartitionAssignment {
            val now = Instant.now(clock)
            return PartitionAssignment(
                partitionNumber = partitionNumber,
                instanceId = instanceId,
                updatedAt = now,
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
    ) {
        val now = Instant.now(clock)

        this.instanceId = instanceId
        this.updatedAt = now
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
        if (instanceId != currentInstanceId) {
            throw IllegalStateException(
                "Could not release partition assignment with partition number $partitionNumber. Instance $currentInstanceId does not own this partition assignment.",
            )
        }

        val now = Instant.now(clock)

        this.instanceId = null
        this.updatedAt = now
    }
}
