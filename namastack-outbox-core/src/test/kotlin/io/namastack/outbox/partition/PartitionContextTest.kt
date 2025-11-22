package io.namastack.outbox.partition

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock

@DisplayName("PartitionContext")
class PartitionContextTest {
    private val clock = Clock.systemUTC()

    @Test
    fun `hasNoPartitionAssignments returns true when empty`() {
        val ctx = PartitionContext("i-1", setOf("i-1"), emptySet(), 10)
        assertThat(ctx.hasNoPartitionAssignments()).isTrue()
    }

    @Test
    fun `countOwnedPartitionAssignments counts only current instance`() {
        val assignments =
            setOf(
                PartitionAssignment.create(0, "i-1", clock),
                PartitionAssignment.create(1, "i-2", clock),
                PartitionAssignment.create(2, "i-1", clock),
            )
        val ctx = PartitionContext("i-1", setOf("i-1", "i-2"), assignments, 10)
        assertThat(ctx.countOwnedPartitionAssignments()).isEqualTo(2)
    }

    @Test
    fun `getAllPartitionNumbersByInstanceId returns partition numbers only for matching instance`() {
        val assignments =
            setOf(
                PartitionAssignment.create(5, "i-2", clock),
                PartitionAssignment.create(3, "i-1", clock),
                PartitionAssignment.create(7, "i-1", clock),
            )
        val ctx = PartitionContext("i-1", setOf("i-1", "i-2"), assignments, 10)
        assertThat(ctx.getAllPartitionNumbersByInstanceId("i-1").sorted()).containsExactly(3, 7)
        assertThat(ctx.getAllPartitionNumbersByInstanceId("i-2")).containsExactly(5)
    }

    @Test
    fun `getStalePartitionAssignments returns assignments whose instanceId not active`() {
        val assignments =
            setOf(
                PartitionAssignment.create(0, "i-1", clock),
                PartitionAssignment.create(1, "i-old", clock),
                PartitionAssignment.create(2, "i-2", clock),
                PartitionAssignment.create(3, "i-gone", clock),
                PartitionAssignment(4, null, PartitionAssignment.create(4, "i-1", clock).assignedAt),
            )
        val ctx = PartitionContext("i-1", setOf("i-1", "i-2"), assignments, 10)
        val stale = ctx.getStalePartitionAssignments().map { it.partitionNumber }.sorted()
        // i-old, i-gone, and null instanceId considered stale
        assertThat(stale).containsExactly(1, 3, 4)
    }
}
