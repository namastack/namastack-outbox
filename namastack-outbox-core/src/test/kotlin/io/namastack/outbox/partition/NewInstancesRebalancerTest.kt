package io.namastack.outbox.partition

import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock

@DisplayName("NewInstancesRebalancer")
class NewInstancesRebalancerTest {
    private val clock = Clock.systemUTC()
    private val repo = mockk<PartitionAssignmentRepository>(relaxed = true)
    private val rebalancer = NewInstancesRebalancer(repo)

    @Test
    fun `does nothing when no new instances`() {
        val assignments =
            setOf(
                PartitionAssignment.create(0, "i-1", clock),
                PartitionAssignment.create(1, "i-1", clock),
            )
        val ctx = PartitionContext("i-1", setOf("i-1"), assignments, targetPartitionCount = 2)
        rebalancer.rebalance(ctx, previousActiveInstanceIds = setOf("i-1"))
        verify(exactly = 0) { repo.releasePartitions(any(), any()) }
    }

    @Test
    fun `releases surplus partitions when new instance detected`() {
        // current owns 4, target 2 -> release 2 (last two sorted)
        val assignments =
            setOf(
                PartitionAssignment.create(3, "i-1", clock),
                PartitionAssignment.create(1, "i-1", clock),
                PartitionAssignment.create(2, "i-1", clock),
                PartitionAssignment.create(0, "i-1", clock),
            )
        val ctx = PartitionContext("i-1", setOf("i-1", "i-2"), assignments, targetPartitionCount = 2)
        rebalancer.rebalance(ctx, previousActiveInstanceIds = setOf("i-1"))
        // Sorted partition numbers owned by i-1: 0,1,2,3 -> takeLast(2) -> 2,3
        verify { repo.releasePartitions(setOf(2, 3), "i-1") }
    }

    @Test
    fun `does nothing when owned equals target even if new instance present`() {
        val assignments =
            setOf(
                PartitionAssignment.create(0, "i-1", clock),
                PartitionAssignment.create(1, "i-2", clock),
            )
        val ctx = PartitionContext("i-1", setOf("i-1", "i-2"), assignments, targetPartitionCount = 1)
        rebalancer.rebalance(ctx, previousActiveInstanceIds = setOf("i-1"))
        verify(exactly = 0) { repo.releasePartitions(any(), any()) }
    }

    @Test
    fun `does nothing when shortage not positive`() {
        val assignments =
            setOf(
                PartitionAssignment.create(0, "i-1", clock),
            )
        val ctx = PartitionContext("i-1", setOf("i-1", "i-2"), assignments, targetPartitionCount = 5)
        // Owned < target -> no release
        rebalancer.rebalance(ctx, previousActiveInstanceIds = setOf("i-1"))
        verify(exactly = 0) { repo.releasePartitions(any(), any()) }
    }
}
