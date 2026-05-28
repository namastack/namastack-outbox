package io.namastack.outbox.observability.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.namastack.outbox.OutboxChannelNameProvider
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.OutboxRecordStatus.COMPLETED
import io.namastack.outbox.OutboxRecordStatus.FAILED
import io.namastack.outbox.OutboxRecordStatus.NEW
import io.namastack.outbox.OutboxRecordStatusRepository
import io.namastack.outbox.instance.OutboxInstanceRegistry
import io.namastack.outbox.observability.OutboxMetricKeyNames
import io.namastack.outbox.observability.OutboxMetricNames
import io.namastack.outbox.partition.PartitionCoordinator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OutboxInstanceMetricsMeterBinderTest {
    private val recordRepository = mockk<OutboxRecordRepository>(relaxed = true)
    private val recordStatusRepository = mockk<OutboxRecordStatusRepository>()
    private val partitionCoordinator = mockk<PartitionCoordinator>(relaxed = true)
    private val instanceRegistry = mockk<OutboxInstanceRegistry>(relaxed = true)
    private val channelNameProvider = OutboxChannelNameProvider { "orders" }
    private val meterRegistry = SimpleMeterRegistry()

    private val meterBinder =
        OutboxInstanceMetricsMeterBinder(
            recordRepository = recordRepository,
            recordStatusRepository = recordStatusRepository,
            partitionCoordinator = partitionCoordinator,
            instanceRegistry = instanceRegistry,
            channelNameProvider = channelNameProvider,
        )

    @Test
    fun `registers canonical record gauges with canonical tags`() {
        every { recordStatusRepository.countByStatus(NEW) } returns 15L
        every { recordStatusRepository.countByStatus(FAILED) } returns 3L
        every { recordStatusRepository.countByStatus(COMPLETED) } returns 42L

        meterBinder.bindTo(meterRegistry)

        assertThat(
            meterRegistry
                .get(OutboxMetricNames.RECORDS)
                .tag(OutboxMetricKeyNames.LowCardinality.RECORD_STATUS, "new")
                .tag(OutboxMetricKeyNames.LowCardinality.CHANNEL, "orders")
                .gauge()
                .value(),
        ).isEqualTo(15.0)

        assertThat(
            meterRegistry
                .get(OutboxMetricNames.RECORDS)
                .tag(OutboxMetricKeyNames.LowCardinality.RECORD_STATUS, "failed")
                .tag(OutboxMetricKeyNames.LowCardinality.CHANNEL, "orders")
                .gauge()
                .value(),
        ).isEqualTo(3.0)

        assertThat(
            meterRegistry
                .get(OutboxMetricNames.RECORDS)
                .tag(OutboxMetricKeyNames.LowCardinality.RECORD_STATUS, "completed")
                .tag(OutboxMetricKeyNames.LowCardinality.CHANNEL, "orders")
                .gauge()
                .value(),
        ).isEqualTo(42.0)
    }

    @Test
    fun `does not register legacy record gauge name`() {
        stubStatusCounts()

        meterBinder.bindTo(meterRegistry)

        assertThat(meterRegistry.meters.map { it.id.name }).doesNotContain("outbox.records.count")
    }

    @Test
    fun `registers canonical state gauge names`() {
        stubStatusCounts()

        meterBinder.bindTo(meterRegistry)

        assertThat(meterRegistry.meters.map { it.id.name })
            .contains(
                OutboxMetricNames.RECORDS,
                OutboxMetricNames.INSTANCE_PARTITIONS_ASSIGNED,
                OutboxMetricNames.INSTANCE_RECORDS_PENDING,
                OutboxMetricNames.CLUSTER_INSTANCES_ACTIVE,
                OutboxMetricNames.CLUSTER_PARTITIONS_UNASSIGNED,
            )
    }

    private fun stubStatusCounts() {
        every { recordStatusRepository.countByStatus(NEW) } returns 1L
        every { recordStatusRepository.countByStatus(FAILED) } returns 1L
        every { recordStatusRepository.countByStatus(COMPLETED) } returns 1L
    }
}
