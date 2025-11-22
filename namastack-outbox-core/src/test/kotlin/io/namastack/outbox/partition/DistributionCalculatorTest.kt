package io.namastack.outbox.partition

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("DistributionCalculator")
class DistributionCalculatorTest {
    @Test
    fun `returns 0 when no active instances`() {
        val count = DistributionCalculator.targetCount("any", emptySet())
        assertThat(count).isEqualTo(0)
    }

    @Test
    fun `returns TOTAL_PARTITIONS when single instance`() {
        val instanceId = "i-1"
        val count = DistributionCalculator.targetCount(instanceId, setOf(instanceId))
        assertThat(count).isEqualTo(PartitionHasher.TOTAL_PARTITIONS)
    }

    @Test
    fun `even distribution when remainder is 0 (two instances)`() {
        val instances = setOf("b", "a") // intentionally unsorted
        val countA = DistributionCalculator.targetCount("a", instances)
        val countB = DistributionCalculator.targetCount("b", instances)
        assertThat(countA).isEqualTo(256 / 2)
        assertThat(countB).isEqualTo(256 / 2)
        assertThat(countA + countB).isEqualTo(PartitionHasher.TOTAL_PARTITIONS)
    }

    @Test
    fun `remainder distributed to first sorted instances (three instances)`() {
        val instances = setOf("c", "a", "b") // sorted -> a,b,c
        val countA = DistributionCalculator.targetCount("a", instances) // index 0 (< remainder)
        val countB = DistributionCalculator.targetCount("b", instances) // index 1 (>= remainder)
        val countC = DistributionCalculator.targetCount("c", instances) // index 2 (>= remainder)
        val base = PartitionHasher.TOTAL_PARTITIONS / 3 // 85
        val remainder = PartitionHasher.TOTAL_PARTITIONS % 3 // 1
        assertThat(remainder).isEqualTo(1)
        assertThat(countA).isEqualTo(base + 1)
        assertThat(countB).isEqualTo(base)
        assertThat(countC).isEqualTo(base)
        assertThat(countA + countB + countC).isEqualTo(PartitionHasher.TOTAL_PARTITIONS)
    }

    @Test
    fun `deterministic regardless of input set order`() {
        val instances1 = setOf("x", "y", "z")
        val instances2 = setOf("z", "x", "y")
        val counts1 = instances1.associateWith { DistributionCalculator.targetCount(it, instances1) }
        val counts2 = instances2.associateWith { DistributionCalculator.targetCount(it, instances2) }
        assertThat(counts1).isEqualTo(counts2)
    }
}
