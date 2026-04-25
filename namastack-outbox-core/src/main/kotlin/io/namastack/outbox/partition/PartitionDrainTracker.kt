package io.namastack.outbox.partition

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks in-flight processing per partition so draining partitions can be
 * released only after local work finishes.
 */
class PartitionDrainTracker {
    private val inFlightByPartition = ConcurrentHashMap<Int, AtomicInteger>()

    fun start(partitionNumber: Int) {
        inFlightByPartition.compute(partitionNumber) { _, current ->
            (current ?: AtomicInteger()).apply { incrementAndGet() }
        }
    }

    fun finish(partitionNumber: Int) {
        inFlightByPartition.computeIfPresent(partitionNumber) { _, current ->
            if (current.decrementAndGet() <= 0) {
                null
            } else {
                current
            }
        }
    }

    fun hasInFlightRecords(partitionNumbers: Set<Int>): Boolean =
        partitionNumbers.any { partitionNumber ->
            inFlightByPartition[partitionNumber]?.get()?.let { it > 0 } == true
        }
}
