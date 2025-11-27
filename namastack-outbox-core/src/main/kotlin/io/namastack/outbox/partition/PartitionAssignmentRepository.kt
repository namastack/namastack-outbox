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
     * Saves partition assignments using optimistic locking.
     *
     * Updates existing assignments or inserts new ones. Uses the version field
     * to detect concurrent modifications. If a partition was modified by another
     * instance, the operation will throw an OptimisticLockingFailureException or a DataIntegrityViolationException
     * (e.g. due to unique constraint violation) if there are conflicts when inserting or updating records.
     * This is expected behavior and should be handled by the caller.
     *
     * The partition assignments must already have the desired state (e.g., new instanceId).
     * The repository only persists them and updates the version field.
     *
     * @param partitionAssignments Set of partition assignments to save
     * @throws org.springframework.dao.OptimisticLockingFailureException if version mismatch detected (concurrent modification)
     * @throws org.springframework.dao.DataIntegrityViolationException on insert/update conflicts (expected behavior)
     */
    fun saveAll(partitionAssignments: Set<PartitionAssignment>)
}
