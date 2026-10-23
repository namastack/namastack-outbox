package io.namastack.outbox.observability.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@DisplayName("OutboxInstanceMetricsMeterBinder")
class OutboxInstanceMetricsMeterBinderTest {
    private val recordRepository = mockk<OutboxRecordRepository>(relaxed = true)
    private val recordStatusRepository = mockk<OutboxRecordStatusRepository>()
    private val partitionCoordinator = mockk<PartitionCoordinator>(relaxed = true)
    private val instanceRegistry = mockk<OutboxInstanceRegistry>(relaxed = true)
    private val channelNameProvider = OutboxChannelNameProvider { "orders" }
    private val meterRegistry = SimpleMeterRegistry()
    private val now = Instant.parse("2026-01-01T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private val meterBinder = createMeterBinder(stopOnFirstFailure = true)

    @Nested
    @DisplayName("Gauge Registration")
    inner class GaugeRegistration {
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
        fun `reads pending records with one batch count across all assigned partitions`() {
            stubStatusCounts()
            val partitions = (0 until 256).toSet()
            every { partitionCoordinator.getAssignedPartitionNumbers() } returns partitions
            every { recordRepository.countRecordsByPartitions(partitions, NEW) } returns 10L

            meterBinder.bindTo(meterRegistry)

            assertThat(
                meterRegistry
                    .get(OutboxMetricNames.INSTANCE_RECORDS_PENDING)
                    .tag(OutboxMetricKeyNames.LowCardinality.CHANNEL, "orders")
                    .gauge()
                    .value(),
            ).isEqualTo(10.0)

            verify(exactly = 1) { recordRepository.countRecordsByPartitions(partitions, NEW) }
            verify(exactly = 0) { recordRepository.countRecordsByPartition(any(), any()) }
        }

        @Test
        fun `returns zero without querying records when no partitions are assigned`() {
            every { partitionCoordinator.getAssignedPartitionNumbers() } returns emptySet()
            meterBinder.bindTo(meterRegistry)

            assertThat(meterRegistry.get(OutboxMetricNames.INSTANCE_RECORDS_PENDING).gauge().value()).isZero()

            verify { recordRepository wasNot Called }
        }

        @Test
        fun `uses current assignments on each pending gauge read`() {
            every { partitionCoordinator.getAssignedPartitionNumbers() } returnsMany listOf(setOf(1, 2), setOf(3))
            every { recordRepository.countRecordsByPartitions(setOf(1, 2), NEW) } returns 10L
            every { recordRepository.countRecordsByPartitions(setOf(3), NEW) } returns 4L
            meterBinder.bindTo(meterRegistry)
            val gauge = meterRegistry.get(OutboxMetricNames.INSTANCE_RECORDS_PENDING).gauge()

            assertThat(gauge.value()).isEqualTo(10.0)
            assertThat(gauge.value()).isEqualTo(4.0)

            verify(exactly = 1) { recordRepository.countRecordsByPartitions(setOf(1, 2), NEW) }
            verify(exactly = 1) { recordRepository.countRecordsByPartitions(setOf(3), NEW) }
            verify(exactly = 0) { recordRepository.countRecordsByPartition(any(), any()) }
        }

        @Test
        fun `returns zero when the pending batch count fails`() {
            every { partitionCoordinator.getAssignedPartitionNumbers() } returns setOf(1, 2)
            every { recordRepository.countRecordsByPartitions(setOf(1, 2), NEW) } throws
                RuntimeException("count failed")
            meterBinder.bindTo(meterRegistry)

            assertThat(meterRegistry.get(OutboxMetricNames.INSTANCE_RECORDS_PENDING).gauge().value()).isZero()
        }

        @Test
        fun `registers cluster and partition gauges`() {
            stubStatusCounts()
            every { partitionCoordinator.getAssignedPartitionNumbers() } returns setOf(1, 2, 3)
            every { instanceRegistry.getActiveInstanceCount() } returns 2
            every {
                partitionCoordinator.getPartitionContext().getPartitionStats().unassignedPartitionsCount
            } returns 5

            meterBinder.bindTo(meterRegistry)

            assertThat(
                meterRegistry
                    .get(OutboxMetricNames.INSTANCE_PARTITIONS_ASSIGNED)
                    .tag(OutboxMetricKeyNames.LowCardinality.CHANNEL, "orders")
                    .gauge()
                    .value(),
            ).isEqualTo(3.0)
            assertThat(
                meterRegistry
                    .get(OutboxMetricNames.CLUSTER_INSTANCES_ACTIVE)
                    .tag(OutboxMetricKeyNames.LowCardinality.CHANNEL, "orders")
                    .gauge()
                    .value(),
            ).isEqualTo(2.0)
            assertThat(
                meterRegistry
                    .get(OutboxMetricNames.CLUSTER_PARTITIONS_UNASSIGNED)
                    .tag(OutboxMetricKeyNames.LowCardinality.CHANNEL, "orders")
                    .gauge()
                    .value(),
            ).isEqualTo(5.0)
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
                    OutboxMetricNames.INSTANCE_RECORDS_OLDEST_AGE,
                    OutboxMetricNames.INSTANCE_RECORDS_OVERDUE_AGE,
                    OutboxMetricNames.CLUSTER_INSTANCES_ACTIVE,
                    OutboxMetricNames.CLUSTER_PARTITIONS_UNASSIGNED,
                )
        }
    }

    @Nested
    @DisplayName("Record Age Gauges")
    inner class RecordAgeGauges {
        private val partitions = setOf(1, 2)

        @Test
        fun `registers age gauges in seconds with only the channel tag`() {
            meterBinder.bindTo(meterRegistry)

            listOf(
                OutboxMetricNames.INSTANCE_RECORDS_OLDEST_AGE,
                OutboxMetricNames.INSTANCE_RECORDS_OVERDUE_AGE,
            ).forEach { name ->
                val gauge = meterRegistry.get(name).gauge()

                assertThat(gauge.id.baseUnit).isEqualTo("seconds")
                assertThat(gauge.id.tags.map { it.key to it.value })
                    .containsExactly(OutboxMetricKeyNames.LowCardinality.CHANNEL to "orders")
            }
        }

        @Test
        fun `reports age of the oldest pending record across assigned partitions`() {
            every { partitionCoordinator.getAssignedPartitionNumbers() } returns partitions
            every { recordRepository.findOldestCreatedAtByPartitions(partitions, NEW) } returns
                now.minusMillis(90_500)
            meterBinder.bindTo(meterRegistry)

            assertThat(meterRegistry.get(OutboxMetricNames.INSTANCE_RECORDS_OLDEST_AGE).gauge().value())
                .isEqualTo(90.5)

            verify(exactly = 1) { recordRepository.findOldestCreatedAtByPartitions(partitions, NEW) }
        }

        @Test
        fun `reports overdue age since the earliest due next retry time`() {
            every { partitionCoordinator.getAssignedPartitionNumbers() } returns partitions
            every { recordRepository.findOldestDueNextRetryAtByPartitions(partitions, NEW, now, true) } returns
                now.minusSeconds(30)
            meterBinder.bindTo(meterRegistry)

            assertThat(meterRegistry.get(OutboxMetricNames.INSTANCE_RECORDS_OVERDUE_AGE).gauge().value())
                .isEqualTo(30.0)
        }

        @Test
        fun `excludes keys blocked by earlier incomplete records when stop on first failure is enabled`() {
            every { partitionCoordinator.getAssignedPartitionNumbers() } returns partitions
            createMeterBinder(stopOnFirstFailure = true).bindTo(meterRegistry)

            meterRegistry.get(OutboxMetricNames.INSTANCE_RECORDS_OVERDUE_AGE).gauge().value()

            verify(exactly = 1) {
                recordRepository.findOldestDueNextRetryAtByPartitions(
                    partitions = partitions,
                    status = NEW,
                    now = now,
                    ignoreRecordKeysWithPreviousFailure = true,
                )
            }
        }

        @Test
        fun `includes keys blocked by earlier incomplete records when stop on first failure is disabled`() {
            every { partitionCoordinator.getAssignedPartitionNumbers() } returns partitions
            createMeterBinder(stopOnFirstFailure = false).bindTo(meterRegistry)

            meterRegistry.get(OutboxMetricNames.INSTANCE_RECORDS_OVERDUE_AGE).gauge().value()

            verify(exactly = 1) {
                recordRepository.findOldestDueNextRetryAtByPartitions(
                    partitions = partitions,
                    status = NEW,
                    now = now,
                    ignoreRecordKeysWithPreviousFailure = false,
                )
            }
        }

        @Test
        fun `reports zero when no records match`() {
            every { partitionCoordinator.getAssignedPartitionNumbers() } returns partitions
            every { recordRepository.findOldestCreatedAtByPartitions(partitions, NEW) } returns null
            every { recordRepository.findOldestDueNextRetryAtByPartitions(partitions, NEW, now, true) } returns null
            meterBinder.bindTo(meterRegistry)

            assertThat(meterRegistry.get(OutboxMetricNames.INSTANCE_RECORDS_OLDEST_AGE).gauge().value()).isZero()
            assertThat(meterRegistry.get(OutboxMetricNames.INSTANCE_RECORDS_OVERDUE_AGE).gauge().value()).isZero()
        }

        @Test
        fun `reports zero without querying records when no partitions are assigned`() {
            every { partitionCoordinator.getAssignedPartitionNumbers() } returns emptySet()
            meterBinder.bindTo(meterRegistry)

            assertThat(meterRegistry.get(OutboxMetricNames.INSTANCE_RECORDS_OLDEST_AGE).gauge().value()).isZero()
            assertThat(meterRegistry.get(OutboxMetricNames.INSTANCE_RECORDS_OVERDUE_AGE).gauge().value()).isZero()

            verify { recordRepository wasNot Called }
        }

        @Test
        fun `reports zero for records created after now`() {
            every { partitionCoordinator.getAssignedPartitionNumbers() } returns partitions
            every { recordRepository.findOldestCreatedAtByPartitions(partitions, NEW) } returns now.plusSeconds(5)
            meterBinder.bindTo(meterRegistry)

            assertThat(meterRegistry.get(OutboxMetricNames.INSTANCE_RECORDS_OLDEST_AGE).gauge().value()).isZero()
        }

        @Test
        fun `uses current assignments on each age gauge read`() {
            every { partitionCoordinator.getAssignedPartitionNumbers() } returnsMany listOf(setOf(1, 2), setOf(3))
            every { recordRepository.findOldestCreatedAtByPartitions(setOf(1, 2), NEW) } returns now.minusSeconds(10)
            every { recordRepository.findOldestCreatedAtByPartitions(setOf(3), NEW) } returns now.minusSeconds(4)
            meterBinder.bindTo(meterRegistry)
            val gauge = meterRegistry.get(OutboxMetricNames.INSTANCE_RECORDS_OLDEST_AGE).gauge()

            assertThat(gauge.value()).isEqualTo(10.0)
            assertThat(gauge.value()).isEqualTo(4.0)
        }
    }

    @Nested
    @DisplayName("Legacy Names")
    inner class LegacyNames {
        @Test
        fun `does not register legacy record gauge name`() {
            stubStatusCounts()

            meterBinder.bindTo(meterRegistry)

            assertThat(meterRegistry.meters.map { it.id.name }).doesNotContain("outbox.records.count")
        }

        @Test
        fun `does not register dropped aggregate pending gauges`() {
            stubStatusCounts()

            meterBinder.bindTo(meterRegistry)

            assertThat(meterRegistry.meters.map { it.id.name })
                .doesNotContain(
                    "outbox.instance.records.pending.max",
                    "outbox.instance.records.pending.avg",
                )
        }
    }

    @Nested
    @DisplayName("Failure Handling")
    inner class FailureHandling {
        @Test
        fun `returns zero when gauge value supplier fails`() {
            stubStatusCounts()
            every { partitionCoordinator.getAssignedPartitionNumbers() } throws RuntimeException("partition failed")

            meterBinder.bindTo(meterRegistry)

            assertThat(
                meterRegistry
                    .get(OutboxMetricNames.INSTANCE_PARTITIONS_ASSIGNED)
                    .tag(OutboxMetricKeyNames.LowCardinality.CHANNEL, "orders")
                    .gauge()
                    .value(),
            ).isEqualTo(0.0)
        }

        @Test
        fun `reports NaN when the oldest record query fails`() {
            every { partitionCoordinator.getAssignedPartitionNumbers() } returns setOf(1, 2)
            every { recordRepository.findOldestCreatedAtByPartitions(setOf(1, 2), NEW) } throws
                RuntimeException("query failed")
            meterBinder.bindTo(meterRegistry)

            assertThat(meterRegistry.get(OutboxMetricNames.INSTANCE_RECORDS_OLDEST_AGE).gauge().value()).isNaN()
        }

        @Test
        fun `reports NaN when the overdue record query fails`() {
            every { partitionCoordinator.getAssignedPartitionNumbers() } returns setOf(1, 2)
            every { recordRepository.findOldestDueNextRetryAtByPartitions(setOf(1, 2), NEW, now, true) } throws
                RuntimeException("query failed")
            meterBinder.bindTo(meterRegistry)

            assertThat(meterRegistry.get(OutboxMetricNames.INSTANCE_RECORDS_OVERDUE_AGE).gauge().value()).isNaN()
        }

        @Test
        fun `reports NaN for age gauges when partition assignments cannot be read`() {
            every { partitionCoordinator.getAssignedPartitionNumbers() } throws RuntimeException("partition failed")
            meterBinder.bindTo(meterRegistry)

            assertThat(meterRegistry.get(OutboxMetricNames.INSTANCE_RECORDS_OLDEST_AGE).gauge().value()).isNaN()
            assertThat(meterRegistry.get(OutboxMetricNames.INSTANCE_RECORDS_OVERDUE_AGE).gauge().value()).isNaN()
        }
    }

    private fun createMeterBinder(stopOnFirstFailure: Boolean) =
        OutboxInstanceMetricsMeterBinder(
            recordRepository = recordRepository,
            recordStatusRepository = recordStatusRepository,
            partitionCoordinator = partitionCoordinator,
            instanceRegistry = instanceRegistry,
            channelNameProvider = channelNameProvider,
            clock = clock,
            stopOnFirstFailure = stopOnFirstFailure,
        )

    private fun stubStatusCounts() {
        every { recordStatusRepository.countByStatus(NEW) } returns 1L
        every { recordStatusRepository.countByStatus(FAILED) } returns 1L
        every { recordStatusRepository.countByStatus(COMPLETED) } returns 1L
    }
}
