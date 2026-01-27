package io.namastack.outbox.partition

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

@DisplayName("PartitionAssignmentCache")
class PartitionAssignmentCacheTest {
    private val partitionAssignmentRepository = mockk<PartitionAssignmentRepository>()

    private lateinit var cache: PartitionAssignmentCache

    @BeforeEach
    fun setUp() {
        cache = PartitionAssignmentCache(partitionAssignmentRepository)
    }

    @Nested
    @DisplayName("getAssignedPartitionNumbers")
    inner class GetAssignedPartitionNumbers {
        @Test
        fun `should return partition numbers from repository on cache miss`() {
            val instanceId = "instance-1"
            val assignments =
                setOf(
                    PartitionAssignment(0, instanceId, Instant.now()),
                    PartitionAssignment(1, instanceId, Instant.now()),
                    PartitionAssignment(2, instanceId, Instant.now()),
                )
            every { partitionAssignmentRepository.findByInstanceId(instanceId) } returns assignments

            val result = cache.getAssignedPartitionNumbers(instanceId)

            assertThat(result).containsExactlyInAnyOrder(0, 1, 2)
            verify(exactly = 1) { partitionAssignmentRepository.findByInstanceId(instanceId) }
        }

        @Test
        fun `should return cached value on subsequent calls`() {
            val instanceId = "instance-1"
            val assignments =
                setOf(
                    PartitionAssignment(0, instanceId, Instant.now()),
                )
            every { partitionAssignmentRepository.findByInstanceId(instanceId) } returns assignments

            cache.getAssignedPartitionNumbers(instanceId)
            cache.getAssignedPartitionNumbers(instanceId)
            cache.getAssignedPartitionNumbers(instanceId)

            verify(exactly = 1) { partitionAssignmentRepository.findByInstanceId(instanceId) }
        }

        @Test
        fun `should return empty set when no partitions assigned`() {
            val instanceId = "instance-1"
            every { partitionAssignmentRepository.findByInstanceId(instanceId) } returns emptySet()

            val result = cache.getAssignedPartitionNumbers(instanceId)

            assertThat(result).isEmpty()
        }

        @Test
        fun `should cache entries per instance id`() {
            val instanceId1 = "instance-1"
            val instanceId2 = "instance-2"
            every { partitionAssignmentRepository.findByInstanceId(instanceId1) } returns
                setOf(
                    PartitionAssignment(0, instanceId1, Instant.now()),
                )
            every { partitionAssignmentRepository.findByInstanceId(instanceId2) } returns
                setOf(
                    PartitionAssignment(1, instanceId2, Instant.now()),
                )

            val result1 = cache.getAssignedPartitionNumbers(instanceId1)
            val result2 = cache.getAssignedPartitionNumbers(instanceId2)

            assertThat(result1).containsExactly(0)
            assertThat(result2).containsExactly(1)
            verify(exactly = 1) { partitionAssignmentRepository.findByInstanceId(instanceId1) }
            verify(exactly = 1) { partitionAssignmentRepository.findByInstanceId(instanceId2) }
        }
    }

    @Nested
    @DisplayName("evictAll")
    inner class EvictAll {
        @Test
        fun `should clear cache and reload from repository on next access`() {
            val instanceId = "instance-1"
            val initialAssignments =
                setOf(
                    PartitionAssignment(0, instanceId, Instant.now()),
                )
            val updatedAssignments =
                setOf(
                    PartitionAssignment(0, instanceId, Instant.now()),
                    PartitionAssignment(1, instanceId, Instant.now()),
                )
            every { partitionAssignmentRepository.findByInstanceId(instanceId) } returnsMany
                listOf(
                    initialAssignments,
                    updatedAssignments,
                )

            val initialResult = cache.getAssignedPartitionNumbers(instanceId)
            cache.evictAll()
            val updatedResult = cache.getAssignedPartitionNumbers(instanceId)

            assertThat(initialResult).containsExactly(0)
            assertThat(updatedResult).containsExactlyInAnyOrder(0, 1)
            verify(exactly = 2) { partitionAssignmentRepository.findByInstanceId(instanceId) }
        }

        @Test
        fun `should clear cache for all instances`() {
            val instanceId1 = "instance-1"
            val instanceId2 = "instance-2"
            every { partitionAssignmentRepository.findByInstanceId(instanceId1) } returns
                setOf(
                    PartitionAssignment(0, instanceId1, Instant.now()),
                )
            every { partitionAssignmentRepository.findByInstanceId(instanceId2) } returns
                setOf(
                    PartitionAssignment(1, instanceId2, Instant.now()),
                )

            cache.getAssignedPartitionNumbers(instanceId1)
            cache.getAssignedPartitionNumbers(instanceId2)
            cache.evictAll()
            cache.getAssignedPartitionNumbers(instanceId1)
            cache.getAssignedPartitionNumbers(instanceId2)

            verify(exactly = 2) { partitionAssignmentRepository.findByInstanceId(instanceId1) }
            verify(exactly = 2) { partitionAssignmentRepository.findByInstanceId(instanceId2) }
        }

        @Test
        fun `should be safe to call when cache is empty`() {
            cache.evictAll()
            // No exception should be thrown
        }
    }
}
