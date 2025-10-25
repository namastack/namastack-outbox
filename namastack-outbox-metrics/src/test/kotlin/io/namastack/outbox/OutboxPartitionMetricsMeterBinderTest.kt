package io.namastack.outbox

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.namastack.outbox.partition.PartitionProcessingStats
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("OutboxPartitionMetricsMeterBinder")
class OutboxPartitionMetricsMeterBinderTest {
    private val partitionMetricsProvider = mockk<OutboxPartitionMetricsProvider>()
    private val meterRegistry = SimpleMeterRegistry()

    private lateinit var meterBinder: OutboxPartitionMetricsMeterBinder

    @BeforeEach
    fun setUp() {
        meterBinder = OutboxPartitionMetricsMeterBinder(partitionMetricsProvider)
    }

    @Test
    fun `registers partition metrics gauges`() {
        val stats =
            PartitionProcessingStats(
                instanceId = "test-instance",
                assignedPartitions = listOf(1, 2, 3),
                pendingRecordsPerPartition = mapOf(1 to 10L, 2 to 5L, 3 to 8L),
                totalPendingRecords = 23L,
            )

        every { partitionMetricsProvider.getProcessingStats() } returns stats
        every { partitionMetricsProvider.getPartitionStats() } returns
            mockk {
                every { totalInstances } returns 2
                every { totalPartitions } returns 256
                every { averagePartitionsPerInstance } returns 128.0
            }

        meterBinder.bindTo(meterRegistry)

        assertThat(meterRegistry.get("outbox.partitions.assigned.count").gauge().value()).isEqualTo(3.0)
        assertThat(meterRegistry.get("outbox.partitions.pending.records.total").gauge().value()).isEqualTo(23.0)
        assertThat(meterRegistry.get("outbox.partitions.pending.records.max").gauge().value()).isEqualTo(10.0)
        assertThat(
            meterRegistry.get("outbox.partitions.pending.records.avg").gauge().value(),
        ).isEqualTo(7.666666666666667)
        assertThat(meterRegistry.get("outbox.cluster.instances.total").gauge().value()).isEqualTo(2.0)
        assertThat(meterRegistry.get("outbox.cluster.partitions.total").gauge().value()).isEqualTo(256.0)
        assertThat(meterRegistry.get("outbox.cluster.partitions.avg_per_instance").gauge().value()).isEqualTo(128.0)
    }

    @Test
    fun `handles empty partitions correctly`() {
        val stats =
            PartitionProcessingStats(
                instanceId = "test-instance",
                assignedPartitions = emptyList(),
                pendingRecordsPerPartition = emptyMap(),
                totalPendingRecords = 0L,
            )

        every { partitionMetricsProvider.getProcessingStats() } returns stats
        every { partitionMetricsProvider.getPartitionStats() } returns
            mockk {
                every { totalInstances } returns 0
                every { totalPartitions } returns 256
                every { averagePartitionsPerInstance } returns 0.0
            }

        meterBinder.bindTo(meterRegistry)

        assertThat(meterRegistry.get("outbox.partitions.assigned.count").gauge().value()).isEqualTo(0.0)
        assertThat(meterRegistry.get("outbox.partitions.pending.records.total").gauge().value()).isEqualTo(0.0)
        assertThat(meterRegistry.get("outbox.partitions.pending.records.max").gauge().value()).isEqualTo(0.0)
        assertThat(meterRegistry.get("outbox.partitions.pending.records.avg").gauge().value()).isEqualTo(0.0)
        assertThat(meterRegistry.get("outbox.cluster.instances.total").gauge().value()).isEqualTo(0.0)
        assertThat(meterRegistry.get("outbox.cluster.partitions.total").gauge().value()).isEqualTo(256.0)
        assertThat(meterRegistry.get("outbox.cluster.partitions.avg_per_instance").gauge().value()).isEqualTo(0.0)
    }

    @Test
    fun `handles single partition correctly`() {
        val stats =
            PartitionProcessingStats(
                instanceId = "test-instance",
                assignedPartitions = listOf(7),
                pendingRecordsPerPartition = mapOf(7 to 42L),
                totalPendingRecords = 42L,
            )

        every { partitionMetricsProvider.getProcessingStats() } returns stats
        every { partitionMetricsProvider.getPartitionStats() } returns
            mockk {
                every { totalInstances } returns 1
                every { totalPartitions } returns 256
                every { averagePartitionsPerInstance } returns 256.0
            }

        meterBinder.bindTo(meterRegistry)

        assertThat(meterRegistry.get("outbox.partitions.assigned.count").gauge().value()).isEqualTo(1.0)
        assertThat(meterRegistry.get("outbox.partitions.pending.records.total").gauge().value()).isEqualTo(42.0)
        assertThat(meterRegistry.get("outbox.partitions.pending.records.max").gauge().value()).isEqualTo(42.0)
        assertThat(meterRegistry.get("outbox.partitions.pending.records.avg").gauge().value()).isEqualTo(42.0)
        assertThat(meterRegistry.get("outbox.cluster.instances.total").gauge().value()).isEqualTo(1.0)
        assertThat(meterRegistry.get("outbox.cluster.partitions.total").gauge().value()).isEqualTo(256.0)
        assertThat(meterRegistry.get("outbox.cluster.partitions.avg_per_instance").gauge().value()).isEqualTo(256.0)
    }

    @Test
    fun `handles zero pending records correctly`() {
        val stats =
            PartitionProcessingStats(
                instanceId = "test-instance",
                assignedPartitions = listOf(1, 2, 3),
                pendingRecordsPerPartition = mapOf(1 to 0L, 2 to 0L, 3 to 0L),
                totalPendingRecords = 0L,
            )

        every { partitionMetricsProvider.getProcessingStats() } returns stats
        every { partitionMetricsProvider.getPartitionStats() } returns
            mockk {
                every { totalInstances } returns 3
                every { totalPartitions } returns 256
                every { averagePartitionsPerInstance } returns 85.33
            }

        meterBinder.bindTo(meterRegistry)

        assertThat(meterRegistry.get("outbox.partitions.assigned.count").gauge().value()).isEqualTo(3.0)
        assertThat(meterRegistry.get("outbox.partitions.pending.records.total").gauge().value()).isEqualTo(0.0)
        assertThat(meterRegistry.get("outbox.partitions.pending.records.max").gauge().value()).isEqualTo(0.0)
        assertThat(meterRegistry.get("outbox.partitions.pending.records.avg").gauge().value()).isEqualTo(0.0)
        assertThat(meterRegistry.get("outbox.cluster.instances.total").gauge().value()).isEqualTo(3.0)
        assertThat(meterRegistry.get("outbox.cluster.partitions.total").gauge().value()).isEqualTo(256.0)
        assertThat(meterRegistry.get("outbox.cluster.partitions.avg_per_instance").gauge().value()).isEqualTo(85.33)
    }
}
