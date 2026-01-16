package io.namastack.outbox.partition

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant

@DisplayName("PartitionContext")
class PartitionContextTest {
    private val clock = Clock.systemUTC()

    @Test
    fun `hasNoPartitionAssignments returns true when empty`() {
        val ctx = PartitionContext("i-1", setOf("i-1"), emptySet())

        assertThat(ctx.hasNoPartitionAssignments()).isTrue()
    }

    @Test
    fun `hasNoPartitionAssignments returns false when assignments exist`() {
        val assignments = setOf(PartitionAssignment.create(0, "i-1", clock, null))
        val ctx = PartitionContext("i-1", setOf("i-1"), assignments)

        assertThat(ctx.hasNoPartitionAssignments()).isFalse()
    }

    @Test
    fun `countOwnedPartitionAssignments counts only current instance`() {
        val assignments =
            setOf(
                PartitionAssignment.create(0, "i-1", clock, null),
                PartitionAssignment.create(1, "i-2", clock, null),
                PartitionAssignment.create(2, "i-1", clock, null),
            )
        val ctx = PartitionContext("i-1", setOf("i-1", "i-2"), assignments)

        assertThat(ctx.countOwnedPartitionAssignments()).isEqualTo(2)
    }

    @Test
    fun `getPartitionAssignmentsByInstanceId returns assignments for matching instance`() {
        val assignments =
            setOf(
                PartitionAssignment.create(5, "i-2", clock, null),
                PartitionAssignment.create(3, "i-1", clock, null),
                PartitionAssignment.create(7, "i-1", clock, null),
            )
        val ctx = PartitionContext("i-1", setOf("i-1", "i-2"), assignments)

        assertThat(
            ctx.getPartitionAssignmentsByInstanceId("i-1").map { it.partitionNumber }.sorted(),
        ).containsExactly(3, 7)
        assertThat(ctx.getPartitionAssignmentsByInstanceId("i-2").map { it.partitionNumber }).containsExactly(5)
    }

    @Test
    fun `getOwnedPartitionAssignments returns only current instance assignments`() {
        val assignments =
            setOf(
                PartitionAssignment.create(0, "i-1", clock, null),
                PartitionAssignment.create(1, "i-2", clock, null),
                PartitionAssignment.create(2, "i-1", clock, null),
            )
        val ctx = PartitionContext("i-1", setOf("i-1", "i-2"), assignments)

        assertThat(ctx.getOwnedPartitionAssignments().map { it.partitionNumber }.sorted()).containsExactly(0, 2)
    }

    @Test
    fun `getStalePartitionAssignments returns assignments from inactive instances`() {
        val assignments =
            setOf(
                PartitionAssignment.create(0, "i-1", clock, null),
                PartitionAssignment.create(1, "i-old", clock, null),
                PartitionAssignment.create(2, "i-2", clock, null),
                PartitionAssignment.create(3, "i-gone", clock, null),
            )
        val ctx = PartitionContext("i-1", setOf("i-1", "i-2"), assignments)
        val stale = ctx.getStalePartitionAssignments().map { it.partitionNumber }.sorted()

        assertThat(stale).containsExactly(1, 3)
    }

    @Test
    fun `getUnassignedPartitionNumbers returns empty when all assigned`() {
        val assignments = (0..255).map { PartitionAssignment.create(it, "i-1", clock, null) }.toSet()
        val ctx = PartitionContext("i-1", setOf("i-1"), assignments)

        assertThat(ctx.getUnassignedPartitionNumbers()).isEmpty()
    }

    @Test
    fun `getUnassignedPartitionNumbers returns unassigned partitions sorted`() {
        val assignments =
            setOf(
                PartitionAssignment.create(0, "i-1", clock, null),
                PartitionAssignment(50, null, Instant.now()),
                PartitionAssignment.create(100, "i-2", clock, null),
                PartitionAssignment(150, null, Instant.now()),
                PartitionAssignment.create(255, "i-1", clock, null),
            )
        val ctx = PartitionContext("i-1", setOf("i-1", "i-2"), assignments)

        assertThat(ctx.getUnassignedPartitionNumbers()).containsExactly(50, 150)
    }

    @Test
    fun `countPartitionsToRelease returns zero when owned equals target`() {
        val assignments = (0..255).map { PartitionAssignment.create(it, "i-1", clock, null) }.toSet()
        val ctx = PartitionContext("i-1", setOf("i-1"), assignments)

        assertThat(ctx.countPartitionsToRelease()).isEqualTo(0)
    }

    @Test
    fun `countPartitionsToRelease returns surplus when owned greater than target`() {
        val i1Partitions = (0..149).map { PartitionAssignment.create(it, "i-1", clock, null) }.toSet()
        val i2Partitions = (150..255).map { PartitionAssignment.create(it, "i-2", clock, null) }.toSet()
        val assignments = i1Partitions + i2Partitions

        val ctx = PartitionContext("i-1", setOf("i-1", "i-2"), assignments)

        assertThat(ctx.countPartitionsToRelease()).isEqualTo(22)
    }

    @Test
    fun `countPartitionsToRelease returns zero when owned less than target`() {
        val assignments = setOf(PartitionAssignment.create(0, "i-1", clock, null))
        val ctx = PartitionContext("i-1", setOf("i-1"), assignments)

        assertThat(ctx.countPartitionsToRelease()).isEqualTo(0)
    }

    @Test
    fun `countPartitionsToClaim returns zero when owned equals target`() {
        val assignments = (0..127).map { PartitionAssignment.create(it, "i-1", clock, null) }.toSet()
        val ctx = PartitionContext("i-1", setOf("i-1", "i-2"), assignments)

        assertThat(ctx.countPartitionsToClaim()).isEqualTo(0)
    }

    @Test
    fun `countPartitionsToClaim returns shortage when owned less than target`() {
        val assignments = setOf(PartitionAssignment.create(0, "i-1", clock, null))
        val ctx = PartitionContext("i-1", setOf("i-1"), assignments)

        assertThat(ctx.countPartitionsToClaim()).isEqualTo(255)
    }

    @Test
    fun `countPartitionsToClaim returns zero when owned greater than target`() {
        val i1Partitions = (0..199).map { PartitionAssignment.create(it, "i-1", clock, null) }.toSet()
        val i2Partitions = (200..227).map { PartitionAssignment.create(it, "i-2", clock, null) }.toSet()
        val i3Partitions = (228..255).map { PartitionAssignment.create(it, "i-3", clock, null) }.toSet()
        val assignments = i1Partitions + i2Partitions + i3Partitions

        val ctx = PartitionContext("i-1", setOf("i-1", "i-2", "i-3"), assignments)

        assertThat(ctx.countPartitionsToClaim()).isEqualTo(0)
    }

    @Test
    fun `countPartitionsToClaim returns target when no assignments`() {
        val ctx = PartitionContext("i-1", setOf("i-1"), emptySet())

        assertThat(ctx.countPartitionsToClaim()).isEqualTo(256)
    }

    @Test
    fun `getPartitionAssignmentsToRelease returns empty when no surplus`() {
        val assignments = (0..127).map { PartitionAssignment.create(it, "i-1", clock, null) }.toSet()
        val ctx = PartitionContext("i-1", setOf("i-1", "i-2"), assignments)

        assertThat(ctx.getPartitionAssignmentsToRelease()).isEmpty()
    }

    @Test
    fun `getPartitionAssignmentsToRelease returns last N assignments to release`() {
        val i1Partitions = (0..149).map { PartitionAssignment.create(it, "i-1", clock, null) }.toSet()
        val i2Partitions = (150..255).map { PartitionAssignment.create(it, "i-2", clock, null) }.toSet()
        val assignments = i1Partitions + i2Partitions

        val ctx = PartitionContext("i-1", setOf("i-1", "i-2"), assignments)
        val toRelease = ctx.getPartitionAssignmentsToRelease()

        assertThat(toRelease.map { it.partitionNumber }).containsExactlyInAnyOrder(*(128..149).toList().toTypedArray())
    }

    @Test
    fun `getPartitionAssignmentsToClaim returns empty when no shortage`() {
        val assignments = (0..127).map { PartitionAssignment.create(it, "i-1", clock, null) }.toSet()
        val ctx = PartitionContext("i-1", setOf("i-1", "i-2"), assignments)

        assertThat(ctx.getPartitionAssignmentsToClaim()).isEmpty()
    }

    @Test
    fun `getPartitionAssignmentsToClaim returns empty when no stale partitions`() {
        val i1Partitions = (0..9).map { PartitionAssignment.create(it, "i-1", clock, null) }.toSet()
        val i2Partitions = (10..255).map { PartitionAssignment.create(it, "i-2", clock, null) }.toSet()
        val assignments = i1Partitions + i2Partitions

        val ctx = PartitionContext("i-1", setOf("i-1", "i-2"), assignments)

        assertThat(ctx.getPartitionAssignmentsToClaim()).isEmpty()
    }

    @Test
    fun `getPartitionAssignmentsToClaim returns empty when insufficient stale partitions`() {
        val i1Partitions = (0..55).map { PartitionAssignment.create(it, "i-1", clock, null) }.toSet()
        val i2Partitions = (56..155).map { PartitionAssignment.create(it, "i-2", clock, null) }.toSet()
        val stalePartitions = (156..205).map { PartitionAssignment.create(it, "i-old", clock, null) }.toSet()
        val assignments = i1Partitions + i2Partitions + stalePartitions

        val ctx = PartitionContext("i-1", setOf("i-1", "i-2"), assignments)

        assertThat(ctx.getPartitionAssignmentsToClaim()).isEmpty()
    }

    @Test
    fun `getPartitionAssignmentsToClaim returns first N stale assignments`() {
        val i1Partitions = (0..99).map { PartitionAssignment.create(it, "i-1", clock, null) }.toSet()
        val i2Partitions = (100..127).map { PartitionAssignment.create(it, "i-2", clock, null) }.toSet()
        val stalePartitions = (128..177).map { PartitionAssignment.create(it, "i-old", clock, null) }.toSet()
        val assignments = i1Partitions + i2Partitions + stalePartitions

        val ctx = PartitionContext("i-1", setOf("i-1", "i-2"), assignments)
        val toClaim = ctx.getPartitionAssignmentsToClaim()

        assertThat(toClaim.map { it.partitionNumber }).containsExactlyInAnyOrder(*(128..155).toList().toTypedArray())
    }

    @Test
    fun `targetPartitionCount is 256 for single instance`() {
        val ctx = PartitionContext("i-1", setOf("i-1"), emptySet())

        assertThat(ctx.targetPartitionCount).isEqualTo(256)
    }

    @Test
    fun `targetPartitionCount is 128 for each instance with 2 instances`() {
        val ctx1 = PartitionContext("i-1", setOf("i-1", "i-2"), emptySet())
        val ctx2 = PartitionContext("i-2", setOf("i-1", "i-2"), emptySet())

        assertThat(ctx1.targetPartitionCount).isEqualTo(128)
        assertThat(ctx2.targetPartitionCount).isEqualTo(128)
    }

    @Test
    fun `targetPartitionCount with remainder favors first instances alphabetically`() {
        val ctx1 = PartitionContext("i-1", setOf("i-1", "i-2", "i-3"), emptySet())
        val ctx2 = PartitionContext("i-2", setOf("i-1", "i-2", "i-3"), emptySet())
        val ctx3 = PartitionContext("i-3", setOf("i-1", "i-2", "i-3"), emptySet())

        assertThat(ctx1.targetPartitionCount).isEqualTo(86)
        assertThat(ctx2.targetPartitionCount).isEqualTo(85)
        assertThat(ctx3.targetPartitionCount).isEqualTo(85)
    }

    @Test
    fun `getPartitionStats returns correct statistics with balanced distribution`() {
        val i1Partitions = (0..127).map { PartitionAssignment.create(it, "i-1", clock, null) }.toSet()
        val i2Partitions = (128..255).map { PartitionAssignment.create(it, "i-2", clock, null) }.toSet()
        val assignments = i1Partitions + i2Partitions

        val ctx = PartitionContext("i-1", setOf("i-1", "i-2"), assignments)
        val stats = ctx.getPartitionStats()

        assertThat(stats.totalPartitions).isEqualTo(256)
        assertThat(stats.totalInstances).isEqualTo(2)
        assertThat(stats.averagePartitionsPerInstance).isEqualTo(128.0)
        assertThat(stats.instanceStats).isEqualTo(mapOf("i-1" to 128, "i-2" to 128))
        assertThat(stats.unassignedPartitionsCount).isEqualTo(0)
        assertThat(stats.unassignedPartitionNumbers).isEmpty()
    }

    @Test
    fun `getPartitionStats returns correct statistics with unassigned partitions`() {
        val i1Partitions = (0..99).map { PartitionAssignment.create(it, "i-1", clock, null) }.toSet()
        val i2Partitions = (100..199).map { PartitionAssignment.create(it, "i-2", clock, null) }.toSet()
        val unassignedPartitions = (200..255).map { PartitionAssignment(it, null, Instant.now()) }.toSet()
        val assignments = i1Partitions + i2Partitions + unassignedPartitions

        val ctx = PartitionContext("i-1", setOf("i-1", "i-2"), assignments)
        val stats = ctx.getPartitionStats()

        assertThat(stats.totalPartitions).isEqualTo(256)
        assertThat(stats.totalInstances).isEqualTo(2)
        assertThat(stats.averagePartitionsPerInstance).isEqualTo(100.0)
        assertThat(stats.instanceStats).isEqualTo(mapOf("i-1" to 100, "i-2" to 100))
        assertThat(stats.unassignedPartitionsCount).isEqualTo(56)
        assertThat(stats.unassignedPartitionNumbers).containsExactlyElementsOf((200..255).toList())
    }

    @Test
    fun `getPartitionStats returns correct statistics with stale instances`() {
        val i1Partitions = (0..99).map { PartitionAssignment.create(it, "i-1", clock, null) }.toSet()
        val i2Partitions = (100..199).map { PartitionAssignment.create(it, "i-2", clock, null) }.toSet()
        val stalePartitions = (200..255).map { PartitionAssignment.create(it, "i-old", clock, null) }.toSet()
        val assignments = i1Partitions + i2Partitions + stalePartitions

        val ctx = PartitionContext("i-1", setOf("i-1", "i-2"), assignments)
        val stats = ctx.getPartitionStats()

        assertThat(stats.totalPartitions).isEqualTo(256)
        assertThat(stats.totalInstances).isEqualTo(3)
        assertThat(stats.averagePartitionsPerInstance).isEqualTo(256.0 / 3)
        assertThat(stats.instanceStats).isEqualTo(mapOf("i-1" to 100, "i-2" to 100, "i-old" to 56))
        assertThat(stats.unassignedPartitionsCount).isEqualTo(0)
        assertThat(stats.unassignedPartitionNumbers).isEmpty()
    }

    @Test
    fun `getPartitionStats returns zero average when no instances own partitions`() {
        val unassignedPartitions = (0..255).map { PartitionAssignment(it, null, Instant.now()) }.toSet()

        val ctx = PartitionContext("i-1", setOf("i-1"), unassignedPartitions)
        val stats = ctx.getPartitionStats()

        assertThat(stats.totalPartitions).isEqualTo(256)
        assertThat(stats.totalInstances).isEqualTo(0)
        assertThat(stats.averagePartitionsPerInstance).isEqualTo(0.0)
        assertThat(stats.instanceStats).isEmpty()
        assertThat(stats.unassignedPartitionsCount).isEqualTo(256)
        assertThat(stats.unassignedPartitionNumbers).containsExactlyElementsOf((0..255).toList())
    }
}
