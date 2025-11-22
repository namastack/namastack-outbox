package io.namastack.outbox.partition

import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock

@DisplayName("StalePartitionsRebalancer")
class StalePartitionsRebalancerTest {
    private val clock = Clock.systemUTC()
    private val repo = mockk<PartitionAssignmentRepository>(relaxed = true)
    private val rebalancer = StalePartitionsRebalancer(repo)

    @Test
    fun `does nothing when target already met`() {
        val assignments =
            setOf(
                PartitionAssignment.create(0, "i-1", clock),
                PartitionAssignment.create(1, "i-1", clock),
            )
        val ctx = PartitionContext("i-1", setOf("i-1"), assignments, targetPartitionCount = 2)
        rebalancer.rebalance(ctx)
        verify(exactly = 0) { repo.claimStalePartitions(any(), any(), any()) }
    }

    @Test
    fun `claims stale partitions up to shortage`() {
        // current owns 1, target 3 => needs 2
        val assignments =
            setOf(
                PartitionAssignment.create(0, "i-1", clock),
                PartitionAssignment.create(1, "gone-1", clock),
                PartitionAssignment.create(2, "gone-2", clock),
                PartitionAssignment.create(3, "active-2", clock),
            )
        val ctx =
            PartitionContext(
                currentInstanceId = "i-1",
                activeInstanceIds = setOf("i-1", "active-2"),
                partitionAssignments = assignments,
                targetPartitionCount = 3,
            )

        rebalancer.rebalance(ctx)
        verify { repo.claimStalePartitions(match { it.size == 2 && it.containsAll(listOf(1, 2)) }, any(), eq("i-1")) }
    }

    @Test
    fun `does not claim more than available stale partitions`() {
        // needs 3 but only 2 stale available => claims those 2
        val assignments =
            setOf(
                PartitionAssignment.create(0, "i-1", clock),
                PartitionAssignment.create(1, "stale-x", clock),
                PartitionAssignment.create(2, "stale-y", clock),
            )
        val ctx = PartitionContext("i-1", setOf("i-1"), assignments, targetPartitionCount = 4)
        rebalancer.rebalance(ctx)
        verify { repo.claimStalePartitions(match { it.size == 2 }, any(), eq("i-1")) }
    }

    @Test
    fun `skips when no stale partitions`() {
        val assignments =
            setOf(
                PartitionAssignment.create(0, "i-1", clock),
                PartitionAssignment.create(1, "i-2", clock),
            )
        val ctx = PartitionContext("i-1", setOf("i-1", "i-2"), assignments, targetPartitionCount = 3)
        rebalancer.rebalance(ctx)
        verify(exactly = 0) { repo.claimStalePartitions(any(), any(), any()) }
    }
}
