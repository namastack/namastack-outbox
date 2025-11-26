package io.namastack.outbox.partition

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock

@DisplayName("PartitionAssignment")
class PartitionAssignmentTest {
    private val clock = Clock.systemUTC()

    @Test
    fun `create sets partitionNumber instanceId and timestamps`() {
        val pa = PartitionAssignment.create(42, "worker-1", clock, null)
        assertThat(pa.partitionNumber).isEqualTo(42)
        assertThat(pa.instanceId).isEqualTo("worker-1")
    }

    @Test
    fun `assignments with different partition number are not equal`() {
        val a = PartitionAssignment.create(1, "x", clock, null)
        val b = PartitionAssignment.create(2, "x", clock, null)
        assertThat(a).isNotEqualTo(b)
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
        val pa = PartitionAssignment.create(9, "x", clock, null)
        val copy = pa.copy(instanceId = "y")
        assertThat(copy.instanceId).isEqualTo("y")
        assertThat(pa.instanceId).isEqualTo("x")
    }

    @Test
    fun `release throws IllegalStateException if instanceId does not match`() {
        val pa = PartitionAssignment.create(1, "owner", clock, null)
        assertThatThrownBy {
            pa.release("other-instance", clock)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("does not own this partition assignment")
    }
}
