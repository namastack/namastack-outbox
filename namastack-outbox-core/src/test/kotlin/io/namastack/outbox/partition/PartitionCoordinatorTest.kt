package io.namastack.outbox.partition

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.OutboxInstanceRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock

@DisplayName("PartitionCoordinator")
class PartitionCoordinatorTest {
    private val clock = Clock.systemUTC()

    private val instanceRegistry: OutboxInstanceRegistry = mockk(relaxed = true)
    private val partitionAssignmentRepository: PartitionAssignmentRepository = mockk(relaxed = true)

    private lateinit var partitionCoordinator: PartitionCoordinator

    @BeforeEach
    fun setUp() {
        every { instanceRegistry.getCurrentInstanceId() } returns "current"

        partitionCoordinator =
            PartitionCoordinator(
                instanceRegistry = instanceRegistry,
                partitionAssignmentRepository = partitionAssignmentRepository,
            )
    }

    @Test
    fun `rebalance does nothing when no active instances`() {
        every { instanceRegistry.getActiveInstanceIds() } returns emptySet()

        partitionCoordinator.rebalance()

        verify(exactly = 0) { partitionAssignmentRepository.findAll() }
        verify(exactly = 0) { partitionAssignmentRepository.claimAllPartitions(any()) }
        verify(exactly = 0) { partitionAssignmentRepository.claimStalePartitions(any(), any(), any()) }
        verify(exactly = 0) { partitionAssignmentRepository.releasePartition(any(), any()) }
    }

    @Test
    fun `bootstrap when no assignments`() {
        every { instanceRegistry.getActiveInstanceIds() } returns setOf("current", "other")
        every { partitionAssignmentRepository.findAll() } returns emptySet()

        partitionCoordinator.rebalance()

        verify(exactly = 1) { partitionAssignmentRepository.claimAllPartitions("current") }
        verify(exactly = 0) { partitionAssignmentRepository.claimStalePartitions(any(), any(), any()) }
        verify(exactly = 0) { partitionAssignmentRepository.releasePartition(any(), any()) }
    }

    @Test
    fun `rebalance with shortage claims stale partitions`() {
        // active instances sorted => [b, c, current]; index current=2 remainder=1 -> base=85 remainder=1 => target=85
        every { instanceRegistry.getActiveInstanceIds() } returns setOf("current", "b", "c")
        val owned = (0 until 80).map { PartitionAssignment.create(it, "current", clock) }
        val other = (80 until 200).map { PartitionAssignment.create(it, "b", clock) }
        val stale = (200 until 210).map { PartitionAssignment.create(it, "stale-x", clock) } // 10 stale partitions
        every { partitionAssignmentRepository.findAll() } returns (owned + other + stale).toSet()

        partitionCoordinator.rebalance()

        verify(exactly = 1) {
            partitionAssignmentRepository.claimStalePartitions(
                match { it.size == 5 },
                any(),
                eq("current"),
            )
        }
        verify(exactly = 0) { partitionAssignmentRepository.releasePartition(any(), any()) }
    }

    @Test
    fun `rebalance with surplus releases partitions`() {
        // surplus scenario: owned=100 target=85 -> release 15
        every { instanceRegistry.getActiveInstanceIds() } returns setOf("current", "b", "c")
        val owned = (0 until 100).map { PartitionAssignment.create(it, "current", clock) }
        val other = (100 until 256).map { PartitionAssignment.create(it, "b", clock) }
        every { partitionAssignmentRepository.findAll() } returns (owned + other).toSet()

        partitionCoordinator.rebalance()

        // Release 15 partitions: last 15 of sorted owned (85..99)
        (85 until 100).forEach { p ->
            verify { partitionAssignmentRepository.releasePartition(p, "current") }
        }
        verify(exactly = 15) { partitionAssignmentRepository.releasePartition(any(), any()) }
        // No stale claim
        verify(exactly = 0) { partitionAssignmentRepository.claimStalePartitions(any(), any(), any()) }
    }

    @Test
    fun `rebalance surplus releases only once when called twice`() {
        every { instanceRegistry.getActiveInstanceIds() } returns setOf("current", "b", "c")
        val owned = (0 until 128).map { PartitionAssignment.create(it, "current", clock) }
        val other = (128 until 256).map { PartitionAssignment.create(it, "b", clock) }
        every { partitionAssignmentRepository.findAll() } returns (owned + other).toSet()

        val coord = partitionCoordinator
        coord.rebalance() // first -> release 15
        coord.rebalance() // second -> no new instances -> should NOT release again

        (85 until 127).forEach { p ->
            verify(exactly = 1) { partitionAssignmentRepository.releasePartition(p, "current") }
        }
    }

    @Test
    fun `getAssignedPartitionNumbers returns owned partition numbers`() {
        every { partitionAssignmentRepository.findByInstanceId("current") } returns
            setOf(
                PartitionAssignment.create(5, "current", clock),
                PartitionAssignment.create(3, "current", clock),
            )
        val numbers = partitionCoordinator.getAssignedPartitionNumbers()
        assertThat(numbers).containsExactlyInAnyOrder(3, 5)
    }

    @Test
    fun `getPartitionStats returns counts including unassigned`() {
        every { partitionAssignmentRepository.findAll() } returns
            setOf(
                PartitionAssignment.create(0, "current", clock),
                PartitionAssignment.create(1, "other", clock),
                PartitionAssignment(2, null, PartitionAssignment.create(0, "current", clock).assignedAt),
                PartitionAssignment(3, null, PartitionAssignment.create(0, "current", clock).assignedAt),
            )
        val stats = partitionCoordinator.getPartitionStats()
        assertThat(stats.totalPartitions).isEqualTo(4)
        assertThat(stats.unassignedPartitionsCount).isEqualTo(2)
        assertThat(stats.unassignedPartitionNumbers).containsExactly(2, 3)
        assertThat(stats.instanceStats["current"]).isEqualTo(1)
        assertThat(stats.instanceStats["other"]).isEqualTo(1)
        assertThat(stats.averagePartitionsPerInstance).isEqualTo(1.0)
    }
}
