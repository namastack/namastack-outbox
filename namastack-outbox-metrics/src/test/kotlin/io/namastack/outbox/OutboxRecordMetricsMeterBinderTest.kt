package io.namastack.outbox

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.namastack.outbox.OutboxRecordStatus.COMPLETED
import io.namastack.outbox.OutboxRecordStatus.FAILED
import io.namastack.outbox.OutboxRecordStatus.NEW
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("OutboxMetricsMeterBinder")
class OutboxRecordMetricsMeterBinderTest {
    private val outboxRecordStatusRepository = mockk<OutboxRecordStatusRepository>()
    private val meterRegistry = SimpleMeterRegistry()

    private lateinit var meterBinder: OutboxRecordMetricsMeterBinder

    @BeforeEach
    fun setUp() {
        meterBinder = OutboxRecordMetricsMeterBinder(outboxRecordStatusRepository)
    }

    @Test
    fun `registers gauges for all record statuses`() {
        every { outboxRecordStatusRepository.countByStatus(NEW) } returns 15L
        every { outboxRecordStatusRepository.countByStatus(FAILED) } returns 3L
        every { outboxRecordStatusRepository.countByStatus(COMPLETED) } returns 42L

        meterBinder.bindTo(meterRegistry)

        assertThat(
            meterRegistry
                .get("outbox.records.count")
                .tag("status", "new")
                .gauge()
                .value(),
        ).isEqualTo(15.0)
        assertThat(
            meterRegistry
                .get("outbox.records.count")
                .tag("status", "failed")
                .gauge()
                .value(),
        ).isEqualTo(3.0)
        assertThat(
            meterRegistry
                .get("outbox.records.count")
                .tag("status", "completed")
                .gauge()
                .value(),
        ).isEqualTo(42.0)
    }

    @Test
    fun `handles zero counts correctly`() {
        every { outboxRecordStatusRepository.countByStatus(NEW) } returns 0L
        every { outboxRecordStatusRepository.countByStatus(FAILED) } returns 0L
        every { outboxRecordStatusRepository.countByStatus(COMPLETED) } returns 0L

        meterBinder.bindTo(meterRegistry)

        assertThat(
            meterRegistry
                .get("outbox.records.count")
                .tag("status", "new")
                .gauge()
                .value(),
        ).isEqualTo(0.0)
        assertThat(
            meterRegistry
                .get("outbox.records.count")
                .tag("status", "failed")
                .gauge()
                .value(),
        ).isEqualTo(0.0)
        assertThat(
            meterRegistry
                .get("outbox.records.count")
                .tag("status", "completed")
                .gauge()
                .value(),
        ).isEqualTo(0.0)
    }

    @Test
    fun `handles large counts correctly`() {
        every { outboxRecordStatusRepository.countByStatus(NEW) } returns 1000000L
        every { outboxRecordStatusRepository.countByStatus(FAILED) } returns 500000L
        every { outboxRecordStatusRepository.countByStatus(COMPLETED) } returns 2000000L

        meterBinder.bindTo(meterRegistry)

        assertThat(
            meterRegistry
                .get("outbox.records.count")
                .tag("status", "new")
                .gauge()
                .value(),
        ).isEqualTo(1000000.0)
        assertThat(
            meterRegistry
                .get("outbox.records.count")
                .tag("status", "failed")
                .gauge()
                .value(),
        ).isEqualTo(500000.0)
        assertThat(
            meterRegistry
                .get("outbox.records.count")
                .tag("status", "completed")
                .gauge()
                .value(),
        ).isEqualTo(
            2000000.0,
        )
    }

    @Test
    fun `gauge descriptions and tags are set correctly`() {
        every { outboxRecordStatusRepository.countByStatus(any()) } returns 5L

        meterBinder.bindTo(meterRegistry)

        val newGauge = meterRegistry.get("outbox.records.count").tag("status", "new").gauge()
        val failedGauge = meterRegistry.get("outbox.records.count").tag("status", "failed").gauge()
        val completedGauge = meterRegistry.get("outbox.records.count").tag("status", "completed").gauge()

        assertThat(newGauge.getId().description).isEqualTo("Count of outbox records by status")
        assertThat(failedGauge.getId().description).isEqualTo("Count of outbox records by status")
        assertThat(completedGauge.getId().description).isEqualTo("Count of outbox records by status")

        assertThat(newGauge.getId().getTag("status")).isEqualTo("new")
        assertThat(failedGauge.getId().getTag("status")).isEqualTo("failed")
        assertThat(completedGauge.getId().getTag("status")).isEqualTo("completed")
    }

    @Test
    fun `gauge values update dynamically`() {
        every { outboxRecordStatusRepository.countByStatus(NEW) } returns 10L
        every { outboxRecordStatusRepository.countByStatus(FAILED) } returns 2L
        every { outboxRecordStatusRepository.countByStatus(COMPLETED) } returns 8L

        meterBinder.bindTo(meterRegistry)

        assertThat(
            meterRegistry
                .get("outbox.records.count")
                .tag("status", "new")
                .gauge()
                .value(),
        ).isEqualTo(10.0)

        every { outboxRecordStatusRepository.countByStatus(NEW) } returns 15L

        assertThat(
            meterRegistry
                .get("outbox.records.count")
                .tag("status", "new")
                .gauge()
                .value(),
        ).isEqualTo(15.0)
    }

    @Test
    fun `registers exactly three gauges for three statuses`() {
        every { outboxRecordStatusRepository.countByStatus(any()) } returns 1L

        meterBinder.bindTo(meterRegistry)

        val meters = meterRegistry.meters
        val outboxRecordMeters = meters.filter { it.id.name == "outbox.records.count" }

        assertThat(outboxRecordMeters).hasSize(3)
        assertThat(outboxRecordMeters.map { it.id.getTag("status") })
            .containsExactlyInAnyOrder("new", "failed", "completed")
    }
}
