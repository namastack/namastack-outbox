package io.namastack.outbox.partition

import java.util.concurrent.ConcurrentHashMap

/**
 * Cache for partition assignments.
 *
 * @author Roland Beisel
 * @since 1.0.0
 */
class PartitionAssignmentCache(
    private val partitionAssignmentRepository: PartitionAssignmentRepository,
) {
    private val cache = ConcurrentHashMap<String, Set<Int>>()

    /**
     * Return partition numbers assigned to the given instance (cached).
     * Loads from repository on cache miss.
     */
    fun getAssignedPartitionNumbers(instanceId: String): Set<Int> =
        cache.computeIfAbsent(instanceId) { id ->
            partitionAssignmentRepository
                .findByInstanceId(id)
                .map { it.partitionNumber }
                .toSet()
        }

    /**
     * Invalidates all cached partition assignments.
     * Should be called after rebalancing to ensure fresh data.
     */
    fun evictAll() {
        cache.clear()
    }
}
