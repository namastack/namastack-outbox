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
     * instance, the operation will throw an OptimisticLockingFailureException.
     *
     * The partition assignments must already have the desired state (e.g., new instanceId).
     * The repository only persists them and updates the version field.
     *
     * @param partitionAssignments Set of partition assignments to save
     * @throws org.springframework.dao.OptimisticLockingFailureException if version mismatch detected (concurrent modification)
     */
    fun saveAll(partitionAssignments: Set<PartitionAssignment>)

    /**
     * Inserts partition assignments only if the assignment table is empty.
     *
     * This method is designed to be called during application startup by multiple instances
     * concurrently. It ensures that only ONE instance will initialize the partition assignments,
     * preventing duplicate initialization attempts.
     *
     * Uses a distributed lock (pessimistic locking) to coordinate between instances:
     * 1. Acquires an exclusive lock on a shared lock entity to serialize access
     * 2. Checks if any partition assignments already exist
     * 3. If table is empty, inserts all assignments atomically
     * 4. If table is not empty, assumes another instance already initialized and returns
     *
     * This approach guarantees:
     * - Only one instance can execute the insert block at a time (due to distributed lock)
     * - Once assignments are created by one instance, all other instances will see them
     *   and skip their initialization attempts
     * - No duplicate data is created despite concurrent startup of multiple instances
     * - No manual coordination or distributed consensus protocol is needed
     *
     * @param partitionAssignments Set of partition assignments to insert if table is empty
     * @return true if this instance initialized the assignments, false if another instance already did
     */
    fun insertIfAllAbsent(partitionAssignments: Set<PartitionAssignment>): Boolean
}
