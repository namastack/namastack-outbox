package io.namastack.outbox.partition

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PartitionHasherTest {
    @Test
    fun `should return consistent partition for same record key`() {
        val recordKey = "customer-123"

        val partition1 = PartitionHasher.getPartitionForRecordKey(recordKey)
        val partition2 = PartitionHasher.getPartitionForRecordKey(recordKey)

        assertThat(partition1).isEqualTo(partition2)
    }

    @Test
    fun `should return different partitions for different record keys`() {
        val recordKey1 = "customer-123"
        val recordKey2 = "customer-456"

        val partition1 = PartitionHasher.getPartitionForRecordKey(recordKey1)
        val partition2 = PartitionHasher.getPartitionForRecordKey(recordKey2)

        assertThat(partition1).isNotEqualTo(partition2)
    }

    @Test
    fun `should return partition within valid range for various record keys`() {
        val testRecordKeys =
            listOf(
                "customer-1",
                "order-abc123",
                "user-xyz789",
                "product-def456",
                "invoice-ghi789",
            )

        testRecordKeys.forEach { recordKey ->
            val partition = PartitionHasher.getPartitionForRecordKey(recordKey)
            assertThat(partition)
                .isGreaterThanOrEqualTo(0)
                .isLessThan(PartitionHasher.TOTAL_PARTITIONS)
        }
    }

    @Test
    fun `should handle empty string gracefully`() {
        val partition = PartitionHasher.getPartitionForRecordKey("")

        assertThat(partition)
            .isGreaterThanOrEqualTo(0)
            .isLessThan(PartitionHasher.TOTAL_PARTITIONS)
    }

    @Test
    fun `should handle special characters correctly`() {
        val specialCharacterIds =
            listOf(
                "customer-with-@-symbol",
                "order#123!",
                "user_with_underscores",
                "product.with.dots",
                "invoice-with-äöü-umlauts",
            )

        specialCharacterIds.forEach { recordKey ->
            val partition = PartitionHasher.getPartitionForRecordKey(recordKey)
            assertThat(partition)
                .isGreaterThanOrEqualTo(0)
                .isLessThan(PartitionHasher.TOTAL_PARTITIONS)
        }
    }

    @Test
    fun `should handle large number of different record keys`() {
        // Test with many record keys to verify no edge cases break the hash function
        val manyRecordKeys = (1..1000).map { "record-key-$it" }

        manyRecordKeys.forEach { recordKey ->
            val partition = PartitionHasher.getPartitionForRecordKey(recordKey)
            assertThat(partition)
                .isGreaterThanOrEqualTo(0)
                .isLessThan(PartitionHasher.TOTAL_PARTITIONS)
        }
    }

    @Test
    fun `should distribute record keys well across partitions`() {
        val manyRecordKeys = (1..1000).map { "record-key-$it" }
        val partitions = manyRecordKeys.map { PartitionHasher.getPartitionForRecordKey(it) }
        val uniquePartitions = partitions.toSet()

        // Expect good distribution - at least 20% of total partitions used
        assertThat(uniquePartitions.size).isGreaterThan(PartitionHasher.TOTAL_PARTITIONS / 5)

        uniquePartitions.forEach { partition ->
            assertThat(partition)
                .isGreaterThanOrEqualTo(0)
                .isLessThan(PartitionHasher.TOTAL_PARTITIONS)
        }
    }

    @Test
    fun `should be case sensitive for record keys`() {
        val lowerCaseId = "customer-abc"
        val upperCaseId = "CUSTOMER-ABC"
        val mixedCaseId = "Customer-Abc"

        val lowerPartition = PartitionHasher.getPartitionForRecordKey(lowerCaseId)
        val upperPartition = PartitionHasher.getPartitionForRecordKey(upperCaseId)
        val mixedPartition = PartitionHasher.getPartitionForRecordKey(mixedCaseId)

        // Different cases should likely produce different partitions
        val partitions = setOf(lowerPartition, upperPartition, mixedPartition)
        assertThat(partitions.size).isGreaterThan(1)
    }

    @Test
    fun `should handle very long record keys`() {
        val longRecordKey = "customer-" + "a".repeat(1000)

        val partition = PartitionHasher.getPartitionForRecordKey(longRecordKey)

        assertThat(partition)
            .isGreaterThanOrEqualTo(0)
            .isLessThan(PartitionHasher.TOTAL_PARTITIONS)
    }

    @Test
    fun `should maintain constant partition count`() {
        // Verify the partition count constant doesn't change accidentally
        assertThat(PartitionHasher.TOTAL_PARTITIONS).isEqualTo(256)
    }

    @Test
    fun `should handle UUID-format strings correctly`() {
        val uuidLikeIds =
            listOf(
                "550e8400-e29b-41d4-a716-446655440000",
                "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
                "6ba7b811-9dad-11d1-80b4-00c04fd430c8",
            )

        // All UUIDs should map to valid partitions
        uuidLikeIds.forEach { recordKey ->
            val partition = PartitionHasher.getPartitionForRecordKey(recordKey)
            assertThat(partition)
                .isGreaterThanOrEqualTo(0)
                .isLessThan(PartitionHasher.TOTAL_PARTITIONS)
        }

        // UUIDs should map to different partitions (very likely)
        val partitions = uuidLikeIds.map { PartitionHasher.getPartitionForRecordKey(it) }
        val uniquePartitions = partitions.toSet()
        assertThat(uniquePartitions.size).isEqualTo(uuidLikeIds.size)
    }
}
