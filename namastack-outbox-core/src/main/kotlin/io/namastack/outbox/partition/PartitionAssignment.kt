package io.namastack.outbox.partition

import java.time.Clock
import java.time.OffsetDateTime

/**
 * Domain model representing a partition assignment.
 *
 * Partitions are work units that are distributed across multiple instances.
 * Each partition is assigned to exactly one instance and can be claimed
 * or reassigned based on instance availability.
 *
 * @param partitionNumber The unique partition identifier (0 to TOTAL_PARTITIONS-1)
 * @param instanceId The ID of the instance currently owning this partition
 * @param assignedAt The timestamp when this partition was assigned
 *
 * @author Roland Beisel
 * @since 0.4.0
 */
data class PartitionAssignment(
    val partitionNumber: Int,
    val instanceId: String?,
    val assignedAt: OffsetDateTime,
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
        ): PartitionAssignment {
            val now = OffsetDateTime.now(clock)
            return PartitionAssignment(
                partitionNumber = partitionNumber,
                instanceId = instanceId,
                assignedAt = now,
            )
        }
    }
}
