package io.namastack.outbox.partition

/**
 * Repository interface for managing partition assignments.
 *
 * Provides operations for:
 * - Claiming and releasing partitions
 * - Querying partition assignments
 * - Bulk operations for rebalancing
 *
 * @author Roland Beisel
 * @since 0.4.0
 */
interface PartitionAssignmentRepository {
    /**
     * Finds all partitions.
     *
     * @return List of all partitions
     */
    fun findAll(): Set<PartitionAssignment>

    /**
     * Returns the partitions currently owned by the given instance.
     *
     * @param instanceId instance identifier
     * @return owned partitions
     */
    fun findByInstanceId(instanceId: String): Set<PartitionAssignment>

    /**
     * Claims a partition for an instance using atomic optimistic locking.
     *
     * @param partitionNumber The partition to claim
     * @param instanceId The instance ID claiming this partition
     */
    fun claimPartition(
        partitionNumber: Int,
        instanceId: String,
    )

    /**
     * Claims a stale partition (from an instance that's no longer active).
     *
     * Atomic operation: only succeeds if partition is still owned by staleInstanceId.
     * If concurrent modification or constraint violation: Exception is thrown.
     *
     * @param partitionNumber The partition to claim
     * @param staleInstanceId The instance ID that currently owns the partition
     * @param newInstanceId The instance ID claiming this partition
     */
    fun claimStalePartition(
        partitionNumber: Int,
        staleInstanceId: String,
        newInstanceId: String,
    )

    /**
     * Claims a set of stale partitions in a single atomic transaction (all-or-nothing).
     *
     * Success conditions:
     * - Every specified partition exists.
     * - Each partition is either (a) unassigned (instanceId == null) OR (b) owned by one of the provided stale instance IDs.
     * - No partition is owned by an active instance (i.e. an instance not contained in staleInstanceIds).
     *
     * Behavior:
     * - If any violation occurs (missing partition, owned by a non-stale instance) an exception is thrown and the whole transaction rolls back.
     * - After successful validation all partitions are atomically reassigned to newInstanceId.
     * - A null or empty staleInstanceIds set means: only completely unassigned partitions may be claimed.
     *
     * Concurrency:
     * - Optimistic locking may raise exceptions under concurrent modifications (rollback). Caller can implement retry logic.
     *
     * @param partitionIds Set of partition numbers to claim.
     * @param staleInstanceIds Set of stale instance IDs whose partitions may be taken over. May be null when only unassigned partitions should be claimed.
     * @param newInstanceId The instance ID that will own these partitions after the operation.
     */
    fun claimStalePartitions(
        partitionIds: Set<Int>,
        staleInstanceIds: Set<String>?,
        newInstanceId: String,
    )

    /**
     * Claims all unassigned partitions for the given instance in a single atomic transaction.
     *
     * Used during bootstrap to claim initial set of partitions.
     * If any partition already exists, transaction fails and exception is thrown (all-or-nothing).
     *
     * @param instanceId The instance ID to claim partitions for
     */
    fun claimAllPartitions(instanceId: String)

    /**
     * Releases a partition owned by the given instance.
     *
     * Atomic operation: only releases if partition is owned by currentInstanceId.
     * Prevents accidental/malicious release of partitions owned by other instances.
     * If partition is not owned by currentInstanceId, exception is thrown.
     *
     * @param partitionNumber The partition to release
     * @param currentInstanceId The instance ID that currently owns this partition
     */
    fun releasePartition(
        partitionNumber: Int,
        currentInstanceId: String,
    )
}
