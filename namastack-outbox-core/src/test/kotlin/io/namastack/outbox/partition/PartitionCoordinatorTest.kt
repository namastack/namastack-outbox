package io.namastack.outbox.partition

import io.mockk.every
import io.mockk.mockk
import io.namastack.outbox.OutboxInstanceRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PartitionCoordinatorTest {
    private val instanceRegistry = mockk<OutboxInstanceRegistry>()
    private lateinit var partitionCoordinator: PartitionCoordinator

    @BeforeEach
    fun setUp() {
        partitionCoordinator = PartitionCoordinator(instanceRegistry)
    }

    @Nested
    inner class GetAssignedPartitions {
        @Test
        fun `should return empty list when no instances are active`() {
            every { instanceRegistry.getActiveInstanceIds() } returns emptySet()

            val result = partitionCoordinator.getAssignedPartitions("instance-1")

            assertThat(result).isEmpty()
        }

        @Test
        fun `should return empty list for unknown instance`() {
            every { instanceRegistry.getActiveInstanceIds() } returns setOf("instance-1", "instance-2")

            val result = partitionCoordinator.getAssignedPartitions("unknown-instance")

            assertThat(result).isEmpty()
        }

        @Test
        fun `should distribute partitions evenly across multiple instances`() {
            val instances = setOf("instance-1", "instance-2", "instance-3")
            every { instanceRegistry.getActiveInstanceIds() } returns instances

            val partitions1 = partitionCoordinator.getAssignedPartitions("instance-1")
            val partitions2 = partitionCoordinator.getAssignedPartitions("instance-2")
            val partitions3 = partitionCoordinator.getAssignedPartitions("instance-3")

            assertThat(partitions1).isNotEmpty()
            assertThat(partitions2).isNotEmpty()
            assertThat(partitions3).isNotEmpty()

            val allPartitions = (partitions1 + partitions2 + partitions3).toSet()
            assertThat(allPartitions).hasSize(PartitionHasher.TOTAL_PARTITIONS)

            val instanceCounts = listOf(partitions1.size, partitions2.size, partitions3.size)
            val expectedSize = PartitionHasher.TOTAL_PARTITIONS / 3
            instanceCounts.forEach { count ->
                assertThat(count).isBetween(expectedSize - 1, expectedSize + 1)
            }
        }

        @Test
        fun `should assign all partitions to single instance`() {
            every { instanceRegistry.getActiveInstanceIds() } returns setOf("only-instance")

            val partitions = partitionCoordinator.getAssignedPartitions("only-instance")

            assertThat(partitions).hasSize(PartitionHasher.TOTAL_PARTITIONS)
            assertThat(partitions).containsExactlyElementsOf(0 until PartitionHasher.TOTAL_PARTITIONS)
        }

        @Test
        fun `should return partitions in sorted order`() {
            every { instanceRegistry.getActiveInstanceIds() } returns setOf("instance-1", "instance-2")

            val partitions = partitionCoordinator.getAssignedPartitions("instance-1")

            assertThat(partitions).isSorted()
        }

        @Test
        fun `should maintain consistent assignments across calls`() {
            every { instanceRegistry.getActiveInstanceIds() } returns setOf("instance-1", "instance-2")

            val firstCall = partitionCoordinator.getAssignedPartitions("instance-1")
            val secondCall = partitionCoordinator.getAssignedPartitions("instance-1")

            assertThat(firstCall).isEqualTo(secondCall)
        }
    }

    @Nested
    inner class GetCurrentAssignments {
        @Test
        fun `should return empty map when no instances are active`() {
            every { instanceRegistry.getActiveInstanceIds() } returns emptySet()

            val assignments = partitionCoordinator.getCurrentAssignments()

            assertThat(assignments).isEmpty()
        }

        @Test
        fun `should return all partition assignments for active instances`() {
            every { instanceRegistry.getActiveInstanceIds() } returns setOf("instance-1", "instance-2")

            val assignments = partitionCoordinator.getCurrentAssignments()

            assertThat(assignments).hasSize(PartitionHasher.TOTAL_PARTITIONS)
            assertThat(assignments.keys).containsExactlyElementsOf(0 until PartitionHasher.TOTAL_PARTITIONS)
            assertThat(assignments.values).allMatch { it in setOf("instance-1", "instance-2") }
        }

        @Test
        fun `should return copy of assignments not affecting internal state`() {
            every { instanceRegistry.getActiveInstanceIds() } returns setOf("instance-1")

            val assignments = partitionCoordinator.getCurrentAssignments().toMutableMap()
            assignments.clear()

            val newAssignments = partitionCoordinator.getCurrentAssignments()
            assertThat(newAssignments).hasSize(PartitionHasher.TOTAL_PARTITIONS)
        }
    }

    @Nested
    inner class GetInstanceForPartition {
        @Test
        fun `should return null for invalid partition when no instances`() {
            every { instanceRegistry.getActiveInstanceIds() } returns emptySet()

            val result = partitionCoordinator.getInstanceForPartition(0)

            assertThat(result).isNull()
        }

        @Test
        fun `should return correct instance for valid partition`() {
            every { instanceRegistry.getActiveInstanceIds() } returns setOf("instance-1", "instance-2")

            val instance = partitionCoordinator.getInstanceForPartition(0)

            assertThat(instance).isIn("instance-1", "instance-2")
        }

        @Test
        fun `should return consistent instance for same partition`() {
            every { instanceRegistry.getActiveInstanceIds() } returns setOf("instance-1", "instance-2")

            val firstCall = partitionCoordinator.getInstanceForPartition(42)
            val secondCall = partitionCoordinator.getInstanceForPartition(42)

            assertThat(firstCall).isEqualTo(secondCall)
        }

        @Test
        fun `should handle all valid partition numbers`() {
            every { instanceRegistry.getActiveInstanceIds() } returns setOf("instance-1")

            for (partition in 0 until PartitionHasher.TOTAL_PARTITIONS) {
                val instance = partitionCoordinator.getInstanceForPartition(partition)
                assertThat(instance).isEqualTo("instance-1")
            }
        }
    }

    @Nested
    inner class GetPartitionStats {
        @Test
        fun `should return empty stats when no instances are active`() {
            every { instanceRegistry.getActiveInstanceIds() } returns emptySet()

            val stats = partitionCoordinator.getPartitionStats()

            assertThat(stats.totalPartitions).isZero()
            assertThat(stats.totalInstances).isZero()
            assertThat(stats.averagePartitionsPerInstance).isZero()
            assertThat(stats.instanceStats).isEmpty()
        }

        @Test
        fun `should calculate correct stats for single instance`() {
            every { instanceRegistry.getActiveInstanceIds() } returns setOf("instance-1")

            val stats = partitionCoordinator.getPartitionStats()

            assertThat(stats.totalPartitions).isEqualTo(PartitionHasher.TOTAL_PARTITIONS)
            assertThat(stats.totalInstances).isEqualTo(1)
            assertThat(stats.averagePartitionsPerInstance).isEqualTo(PartitionHasher.TOTAL_PARTITIONS.toDouble())
            assertThat(stats.instanceStats).containsEntry("instance-1", PartitionHasher.TOTAL_PARTITIONS)
        }

        @Test
        fun `should calculate correct stats for multiple instances`() {
            every { instanceRegistry.getActiveInstanceIds() } returns setOf("instance-1", "instance-2", "instance-3")

            val stats = partitionCoordinator.getPartitionStats()

            assertThat(stats.totalPartitions).isEqualTo(PartitionHasher.TOTAL_PARTITIONS)
            assertThat(stats.totalInstances).isEqualTo(3)
            assertThat(stats.averagePartitionsPerInstance)
                .isCloseTo(
                    PartitionHasher.TOTAL_PARTITIONS / 3.0,
                    org.assertj.core.data.Offset
                        .offset(0.5),
                )
            assertThat(stats.instanceStats).hasSize(3)
            assertThat(stats.instanceStats.values.sum()).isEqualTo(PartitionHasher.TOTAL_PARTITIONS)
        }

        @Test
        fun `should show balanced distribution across instances`() {
            every { instanceRegistry.getActiveInstanceIds() } returns setOf("instance-1", "instance-2", "instance-4")

            val stats = partitionCoordinator.getPartitionStats()

            val expectedPerInstance = PartitionHasher.TOTAL_PARTITIONS / 3
            stats.instanceStats.values.forEach { count ->
                assertThat(count).isBetween(expectedPerInstance - 1, expectedPerInstance + 1)
            }
        }
    }

    @Nested
    inner class RebalancingBehavior {
        @Test
        fun `should handle instance changes correctly`() {
            every { instanceRegistry.getActiveInstanceIds() } returns setOf("instance-1", "instance-2")

            partitionCoordinator.getCurrentAssignments()

            every { instanceRegistry.getActiveInstanceIds() } returns setOf("instance-1", "instance-2", "instance-3")
            partitionCoordinator.checkForRebalancing()

            val newAssignments = partitionCoordinator.getCurrentAssignments()

            assertThat(newAssignments).hasSize(PartitionHasher.TOTAL_PARTITIONS)
            assertThat(newAssignments.values).allMatch { it in setOf("instance-1", "instance-2", "instance-3") }
            assertThat(newAssignments.values.toSet()).hasSize(3)
        }

        @Test
        fun `should maintain partition assignments when instances unchanged`() {
            every { instanceRegistry.getActiveInstanceIds() } returns setOf("instance-1", "instance-2")

            val initialAssignments = partitionCoordinator.getCurrentAssignments()
            partitionCoordinator.checkForRebalancing()
            val unchangedAssignments = partitionCoordinator.getCurrentAssignments()

            assertThat(unchangedAssignments).isEqualTo(initialAssignments)
        }

        @Test
        fun `should clear assignments when all instances are removed`() {
            every { instanceRegistry.getActiveInstanceIds() } returns setOf("instance-1")
            partitionCoordinator.getCurrentAssignments()
            partitionCoordinator.checkForRebalancing()

            every { instanceRegistry.getActiveInstanceIds() } returns emptySet()
            partitionCoordinator.checkForRebalancing()

            val assignments = partitionCoordinator.getCurrentAssignments()
            assertThat(assignments).isEmpty()
        }

        @Test
        fun `should redistribute when instance is removed`() {
            every { instanceRegistry.getActiveInstanceIds() } returns setOf("instance-1", "instance-2", "instance-3")
            val threeInstanceAssignments = partitionCoordinator.getCurrentAssignments()

            every { instanceRegistry.getActiveInstanceIds() } returns setOf("instance-1", "instance-2")
            partitionCoordinator.checkForRebalancing()

            val twoInstanceAssignments = partitionCoordinator.getCurrentAssignments()

            assertThat(twoInstanceAssignments).hasSize(PartitionHasher.TOTAL_PARTITIONS)
            assertThat(twoInstanceAssignments.values).allMatch { it in setOf("instance-1", "instance-2") }
            assertThat(twoInstanceAssignments.values.toSet()).hasSize(2)
            assertThat(twoInstanceAssignments).isNotEqualTo(threeInstanceAssignments)
        }
    }

    @Nested
    inner class EdgeCases {
        @Test
        fun `should handle very long instance IDs`() {
            val longInstanceId = "instance-" + "a".repeat(1000)
            every { instanceRegistry.getActiveInstanceIds() } returns setOf(longInstanceId)

            val partitions = partitionCoordinator.getAssignedPartitions(longInstanceId)

            assertThat(partitions).hasSize(PartitionHasher.TOTAL_PARTITIONS)
        }

        @Test
        fun `should handle special characters in instance IDs`() {
            val specialInstanceId = "instance-@#$%^&*()_+-={}[]|\\:;\"'<>,.?/"
            every { instanceRegistry.getActiveInstanceIds() } returns setOf(specialInstanceId)

            val partitions = partitionCoordinator.getAssignedPartitions(specialInstanceId)

            assertThat(partitions).hasSize(PartitionHasher.TOTAL_PARTITIONS)
        }

        @Test
        fun `should maintain deterministic assignment with same instance set`() {
            val instances = setOf("zebra-instance", "alpha-instance", "beta-instance")
            every { instanceRegistry.getActiveInstanceIds() } returns instances

            val firstAssignment = partitionCoordinator.getCurrentAssignments()

            val newCoordinator = PartitionCoordinator(instanceRegistry)
            val secondAssignment = newCoordinator.getCurrentAssignments()

            assertThat(secondAssignment).isEqualTo(firstAssignment)
        }
    }
}
