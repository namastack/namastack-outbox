package io.namastack.outbox.partition

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock

@DisplayName("PartitionAssignment")
class PartitionAssignmentTest {
    private val clock = Clock.systemUTC()

    @Test
    fun `create sets partitionNumber instanceId and timestamps`() {
        val pa = PartitionAssignment.create(42, "worker-1", clock)
        assertThat(pa.partitionNumber).isEqualTo(42)
        assertThat(pa.instanceId).isEqualTo("worker-1")
        assertThat(pa.assignedAt).isNotNull()
    }

    @Test
    fun `assignments with different partition number are not equal`() {
        val a = PartitionAssignment.create(1, "x", clock)
        val b = PartitionAssignment.create(2, "x", clock)
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `assignedAt is near current time (within tolerance)`() {
        val before =
            java.time.OffsetDateTime
                .now(clock)
                .minusSeconds(1)
        val pa = PartitionAssignment.create(5, "x", clock)
        val after =
            java.time.OffsetDateTime
                .now(clock)
                .plusSeconds(1)
        assertThat(pa.assignedAt).isBetween(before, after)
    }

    @Test
    fun `nullable instanceId allowed`() {
        val now = java.time.OffsetDateTime.now(clock)
        val pa = PartitionAssignment(7, null, now)
        assertThat(pa.instanceId).isNull()
        assertThat(pa.partitionNumber).isEqualTo(7)
    }

    @Test
    fun `copy preserves immutability`() {
        val pa = PartitionAssignment.create(9, "x", clock)
        val copy = pa.copy(instanceId = "y")
        assertThat(copy.instanceId).isEqualTo("y")
        assertThat(pa.instanceId).isEqualTo("x")
    }
}
