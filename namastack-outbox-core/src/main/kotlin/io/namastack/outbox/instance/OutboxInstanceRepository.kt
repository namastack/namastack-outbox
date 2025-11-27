package io.namastack.outbox.instance

import java.time.OffsetDateTime

/**
 * Repository interface for managing outbox instance records.
 *
 * Provides operations for instance registration, heartbeat management,
 * and cleanup of stale instances in a distributed outbox processing environment.
 *
 * @author Roland Beisel
 * @since 0.2.0
 */
interface OutboxInstanceRepository {
    /**
     * Saves an instance to the repository.
     *
     * @param instance The instance to save
     * @return The saved instance
     */
    fun save(instance: OutboxInstance): OutboxInstance

    /**
     * Finds an instance by its ID.
     *
     * @param instanceId The instance ID to search for
     * @return The instance if found, null otherwise
     */
    fun findById(instanceId: String): OutboxInstance?

    /**
     * Finds all instances in the repository.
     *
     * @return List of all instances ordered by creation time
     */
    fun findAll(): List<OutboxInstance>

    /**
     * Finds instances by their status.
     *
     * @param status The status to filter by
     * @return List of instances with the specified status
     */
    fun findByStatus(status: OutboxInstanceStatus): List<OutboxInstance>

    /**
     * Finds all active instances.
     *
     * @return List of instances with ACTIVE status
     */
    fun findActiveInstances(): List<OutboxInstance>

    /**
     * Finds instances with stale heartbeats (older than cutoff time).
     *
     * @param cutoffTime The cutoff time for stale heartbeats
     * @return List of instances with heartbeats older than cutoff time
     */
    fun findInstancesWithStaleHeartbeat(cutoffTime: OffsetDateTime): List<OutboxInstance>

    /**
     * Updates the heartbeat timestamp for an instance.
     *
     * @param instanceId The instance ID to update
     * @param timestamp The new heartbeat timestamp
     * @return true if the update was successful, false if instance not found
     */
    fun updateHeartbeat(
        instanceId: String,
        timestamp: OffsetDateTime,
    ): Boolean

    /**
     * Updates the status of an instance.
     *
     * @param instanceId The instance ID to update
     * @param status The new status
     * @param timestamp The timestamp of the status change
     * @return true if the update was successful, false if instance not found
     */
    fun updateStatus(
        instanceId: String,
        status: OutboxInstanceStatus,
        timestamp: OffsetDateTime,
    ): Boolean

    /**
     * Deletes an instance by its ID.
     *
     * @param instanceId The instance ID to delete
     * @return true if the deletion was successful, false if instance not found
     */
    fun deleteById(instanceId: String): Boolean

    /**
     * Counts instances by status.
     *
     * @param status The status to count
     * @return The count of instances with the specified status
     */
    fun countByStatus(status: OutboxInstanceStatus): Long
}
