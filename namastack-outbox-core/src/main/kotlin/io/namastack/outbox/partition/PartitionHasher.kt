package io.namastack.outbox.partition

import org.apache.commons.codec.digest.MurmurHash3

/**
 * Utility for calculating partition assignments using consistent hashing.
 *
 * This class provides deterministic partition assignment for aggregate IDs,
 * ensuring that the same aggregate ID always maps to the same partition.
 *
 * @author Roland Beisel
 * @since 0.2.0
 */
object PartitionHasher {
    /**
     * Total number of partitions. This should never be changed once set in production
     * as it would cause all existing records to be redistributed incorrectly.
     */
    const val TOTAL_PARTITIONS = 256

    /**
     * Calculates the partition number for a given aggregate ID.
     *
     * Uses consistent hashing to ensure the same aggregate ID always
     * maps to the same partition, regardless of the number of instances.
     *
     * @param aggregateId The aggregate ID to calculate partition for
     * @return Partition number between 0 and TOTAL_PARTITIONS-1
     */
    fun getPartitionForAggregate(aggregateId: String): Int {
        val bytes = aggregateId.toByteArray(Charsets.UTF_8)
        val hash = MurmurHash3.hash32x86(bytes, 0, bytes.size, 0)
        val positiveHash = hash and 0x7fffffff

        return positiveHash % TOTAL_PARTITIONS
    }
}
