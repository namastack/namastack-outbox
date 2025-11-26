package io.namastack.outbox

import io.mockk.every
import io.mockk.mockk
import io.namastack.outbox.instance.OutboxInstanceRegistry
import io.namastack.outbox.partition.PartitionCoordinator
import io.namastack.outbox.partition.PartitionStats
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OutboxPartitionMetricsProviderTest {
    private val recordRepository = mockk<OutboxRecordRepository>()
    private val partitionCoordinator = mockk<PartitionCoordinator>()
    private val instanceRegistry = mockk<OutboxInstanceRegistry>()
    private val provider = OutboxPartitionMetricsProvider(recordRepository, partitionCoordinator, instanceRegistry)

    @Test
    fun `getProcessingStats returns correct stats`() {
        every { instanceRegistry.getCurrentInstanceId() } returns "instance-1"
        every { partitionCoordinator.getAssignedPartitionNumbers() } returns setOf(1, 2)
        every { recordRepository.countRecordsByPartition(1, OutboxRecordStatus.NEW) } returns 5L
        every { recordRepository.countRecordsByPartition(2, OutboxRecordStatus.NEW) } returns 10L

        val stats = provider.getProcessingStats()

        assertThat(stats.instanceId).isEqualTo("instance-1")
        assertThat(stats.assignedPartitions).containsExactly(1, 2)
        assertThat(stats.pendingRecordsPerPartition).containsExactlyEntriesOf(mapOf(1 to 5L, 2 to 10L))
        assertThat(stats.totalPendingRecords).isEqualTo(15L)
    }

    @Test
    fun `getPartitionStats delegates to coordinator`() {
        val expectedStats =
            PartitionStats(
                totalPartitions = 2,
                totalInstances = 1,
                averagePartitionsPerInstance = 2.0,
                instanceStats = mapOf("instance-1" to 2),
                unassignedPartitionsCount = 0,
                unassignedPartitionNumbers = emptyList(),
            )

        every { partitionCoordinator.getPartitionContext().getPartitionStats() } returns expectedStats

        val stats = provider.getPartitionStats()

        assertThat(stats).isEqualTo(expectedStats)
    }
}
