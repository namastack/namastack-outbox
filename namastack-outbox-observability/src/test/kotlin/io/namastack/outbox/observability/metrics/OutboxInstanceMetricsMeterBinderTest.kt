package io.namastack.outbox.observability.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.OutboxChannelNameProvider
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.OutboxRecordStatus
import io.namastack.outbox.OutboxRecordStatus.COMPLETED
import io.namastack.outbox.OutboxRecordStatus.FAILED
import io.namastack.outbox.OutboxRecordStatus.NEW
import io.namastack.outbox.instance.OutboxInstanceRegistry
import io.namastack.outbox.observability.OutboxMetricKeyNames
import io.namastack.outbox.observability.OutboxMetricNames
import io.namastack.outbox.partition.PartitionCoordinator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class OutboxInstanceMetricsMeterBinderTest {
    private val recordRepository = mockk<OutboxRecordRepository>()
    private val coordinator = mockk<PartitionCoordinator>(relaxed = true)
    private val instanceRegistry = mockk<OutboxInstanceRegistry>()
    private val now = AtomicReference(Instant.parse("2026-10-22T12:00:00Z"))
    private val clock = mockk<Clock> { every { instant() } answers { now.get() } }
    private val properties = OutboxMetricsProperties()
    private val taskScheduler = ThreadPoolTaskScheduler().apply { initialize() }
    private val collector = createCollector()
    private val binder = OutboxInstanceMetricsMeterBinder(collector) { "orders" }
    private val registry = SimpleMeterRegistry()

    private fun createCollector(stopOnFirstFailure: Boolean = true) =
        OutboxMetricsCollector(
            recordRepository,
            coordinator,
            instanceRegistry,
            properties,
            clock,
            taskScheduler,
            stopOnFirstFailure,
        )

    @AfterEach
    fun tearDown() {
        collector.stop()
        taskScheduler.shutdown()
        registry.close()
    }

    @BeforeEach
    fun setUp() {
        every { recordRepository.countByStatus(NEW) } returns 15
        every { recordRepository.countByStatus(FAILED) } returns 3
        every { recordRepository.countByStatus(COMPLETED) } returns 42
        every { coordinator.getAssignedPartitionNumbers() } returns setOf(1, 2)
        every { recordRepository.findOldestCreatedAtByPartitions(any(), NEW) } returns null
        every { recordRepository.findOldestDueNextRetryAtByPartitions(any(), NEW, any(), any()) } returns null
        every { recordRepository.countRecordsByPartitions(setOf(1, 2), NEW) } returns 10
        every { instanceRegistry.getActiveInstanceCount() } returns 2
        every { coordinator.getPartitionContext().getPartitionStats().unassignedPartitionsCount } returns 5
        binder.bindTo(registry)
    }

    @Test
    fun `binding and reading uninitialized gauges never queries infrastructure`() {
        registry.meters.filterIsInstance<Gauge>().forEach { assertThat(it.value()).isNaN() }
        verify { listOf(recordRepository, coordinator, instanceRegistry) wasNot Called }
    }

    @Test
    fun `publishes collected values with existing names and channel and status tags`() {
        collector.refresh()
        mapOf(NEW to 15.0, FAILED to 3.0, COMPLETED to 42.0).forEach { (status, count) ->
            assertThat(recordGauge(status)).isEqualTo(count)
        }
        assertThat(value(OutboxMetricNames.INSTANCE_RECORDS_PENDING)).isEqualTo(10.0)
        assertThat(value(OutboxMetricNames.INSTANCE_PARTITIONS_ASSIGNED)).isEqualTo(2.0)
        assertThat(value(OutboxMetricNames.CLUSTER_INSTANCES_ACTIVE)).isEqualTo(2.0)
        assertThat(value(OutboxMetricNames.CLUSTER_PARTITIONS_UNASSIGNED)).isEqualTo(5.0)
        assertThat(
            value(OutboxMetricNames.METRICS_COLLECTION_LAST_SUCCESS_TIMESTAMP),
        ).isEqualTo(now.get().epochSecond.toDouble())
        assertThat(registry.meters).hasSize(10)
        assertThat(registry.meters.map { it.id.name }).doesNotContain(
            "outbox.records.count",
            "outbox.instance.records.pending.max",
            "outbox.instance.records.pending.avg",
        )
        registry.meters.forEach {
            assertThat(
                it.id.getTag(OutboxMetricKeyNames.LowCardinality.CHANNEL),
            ).isEqualTo("orders")
        }
    }

    @Test
    fun `many gauge reads and multiple registries use one collection`() {
        collector.refresh()
        SimpleMeterRegistry().useRegistry { other ->
            binder.bindTo(other)
            repeat(5) {
                registry.meters.filterIsInstance<Gauge>().forEach { it.value() }
                other.meters.filterIsInstance<Gauge>().forEach { it.value() }
            }
        }
        OutboxRecordStatus.entries.forEach { verify(exactly = 1) { recordRepository.countByStatus(it) } }
        verify(exactly = 1) { coordinator.getAssignedPartitionNumbers() }
        verify(exactly = 1) { recordRepository.findOldestCreatedAtByPartitions(setOf(1, 2), NEW) }
        verify(exactly = 1) { recordRepository.findOldestDueNextRetryAtByPartitions(setOf(1, 2), NEW, now.get(), true) }
        verify(exactly = 1) { recordRepository.countRecordsByPartitions(setOf(1, 2), NEW) }
        verify(exactly = 1) { instanceRegistry.getActiveInstanceCount() }
        verify(exactly = 1) { coordinator.getPartitionContext() }
        verify(exactly = 0) { recordRepository.countRecordsByPartition(any(), any()) }
    }

    @Test
    fun `refresh uses current assignments and skips record query for no assignments`() {
        collector.refresh()
        every { coordinator.getAssignedPartitionNumbers() } returns setOf(3)
        every { recordRepository.countRecordsByPartitions(setOf(3), NEW) } returns 4
        collector.refresh()
        assertThat(value(OutboxMetricNames.INSTANCE_RECORDS_PENDING)).isEqualTo(4.0)
        every { coordinator.getAssignedPartitionNumbers() } returns emptySet()
        collector.refresh()
        assertThat(value(OutboxMetricNames.INSTANCE_RECORDS_PENDING)).isZero()
        assertThat(value(OutboxMetricNames.INSTANCE_PARTITIONS_ASSIGNED)).isZero()
        verify(exactly = 2) { recordRepository.countRecordsByPartitions(any(), any()) }
    }

    @Test
    fun `failed queries become unknown independently and recover on next refresh`() {
        collector.refresh()
        val lastSuccess = value(OutboxMetricNames.METRICS_COLLECTION_LAST_SUCCESS_TIMESTAMP)
        now.set(now.get().plusSeconds(15))
        every { recordRepository.countByStatus(FAILED) } throws IllegalStateException("database unavailable")
        every { recordRepository.countRecordsByPartitions(any(), any()) } throws IllegalStateException("count failed")
        every { instanceRegistry.getActiveInstanceCount() } throws IllegalStateException("instance count failed")
        collector.refresh()
        assertThat(recordGauge(FAILED)).isNaN()
        assertThat(recordGauge(NEW)).isEqualTo(15.0)
        assertThat(value(OutboxMetricNames.INSTANCE_RECORDS_PENDING)).isNaN()
        assertThat(value(OutboxMetricNames.CLUSTER_INSTANCES_ACTIVE)).isNaN()
        assertThat(value(OutboxMetricNames.CLUSTER_PARTITIONS_UNASSIGNED)).isEqualTo(5.0)
        assertThat(value(OutboxMetricNames.METRICS_COLLECTION_LAST_SUCCESS_TIMESTAMP)).isEqualTo(lastSuccess)

        every { recordRepository.countByStatus(FAILED) } returns 0
        every { recordRepository.countRecordsByPartitions(any(), any()) } returns 0
        every { instanceRegistry.getActiveInstanceCount() } returns 1
        collector.refresh()
        assertThat(recordGauge(FAILED)).isZero()
        assertThat(value(OutboxMetricNames.INSTANCE_RECORDS_PENDING)).isZero()
        assertThat(value(OutboxMetricNames.CLUSTER_INSTANCES_ACTIVE)).isEqualTo(1.0)
        assertThat(
            value(OutboxMetricNames.METRICS_COLLECTION_LAST_SUCCESS_TIMESTAMP),
        ).isEqualTo(now.get().epochSecond.toDouble())
    }

    @Test
    fun `assignment lookup failure makes assigned and pending unknown and skips pending query`() {
        every { coordinator.getAssignedPartitionNumbers() } throws IllegalStateException("assignment lookup failed")
        every { coordinator.getPartitionContext() } throws IllegalStateException("context lookup failed")
        collector.refresh()
        assertThat(value(OutboxMetricNames.INSTANCE_PARTITIONS_ASSIGNED)).isNaN()
        assertThat(value(OutboxMetricNames.INSTANCE_RECORDS_PENDING)).isNaN()
        assertThat(value(OutboxMetricNames.INSTANCE_RECORDS_OLDEST_AGE)).isNaN()
        assertThat(value(OutboxMetricNames.INSTANCE_RECORDS_OVERDUE_AGE)).isNaN()
        assertThat(value(OutboxMetricNames.CLUSTER_PARTITIONS_UNASSIGNED)).isNaN()
        assertThat(value(OutboxMetricNames.CLUSTER_INSTANCES_ACTIVE)).isEqualTo(2.0)
        verify(exactly = 0) {
            recordRepository.countRecordsByPartitions(any(), any())
            recordRepository.findOldestCreatedAtByPartitions(any(), any())
            recordRepository.findOldestDueNextRetryAtByPartitions(any(), any(), any(), any())
        }
    }

    @Test
    fun `readings expire without requiring another refresh`() {
        collector.refresh()
        now.set(now.get().plusSeconds(59))
        assertThat(recordGauge(NEW)).isEqualTo(15.0)
        now.set(now.get().plusSeconds(1))
        registry.meters
            .filterIsInstance<Gauge>()
            .filter {
                it.id.name != OutboxMetricNames.METRICS_COLLECTION_LAST_SUCCESS_TIMESTAMP
            }.forEach { assertThat(it.value()).isNaN() }
        assertThat(value(OutboxMetricNames.METRICS_COLLECTION_LAST_SUCCESS_TIMESTAMP)).isFinite()
        collector.refresh()
        assertThat(recordGauge(NEW)).isEqualTo(15.0)
    }

    @Test
    fun `prometheus scrape remains available while database collection is blocked`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        PrometheusMeterRegistry(PrometheusConfig.DEFAULT).useRegistry { prometheus ->
            binder.bindTo(prometheus)
            prometheus.counter("unrelated.requests").increment()
            collector.refresh()
            every { recordRepository.countByStatus(NEW) } answers {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                20L
            }
            val refresh = worker.submit { collector.refresh() }
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
                now.set(now.get().plusSeconds(60))
                assertTimeoutPreemptively(Duration.ofSeconds(2)) {
                    val scrape = prometheus.scrape()
                    assertThat(scrape).contains("unrelated_requests_total 1.0")
                    assertThat(scrape).contains(
                        "outbox_metrics_collection_last_success_timestamp_seconds{outbox_channel=\"orders\"} " +
                            Instant.parse("2026-10-22T12:00:00Z").epochSecond.toDouble(),
                    )
                    assertThat(
                        scrape,
                    ).contains("outbox_records{outbox_channel=\"orders\",outbox_record_status=\"new\"} NaN")
                }
            } finally {
                release.countDown()
                worker.shutdownNow()
            }
            refresh.get(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `collector starts asynchronously only once and stops without waiting for database`() {
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { recordRepository.countByStatus(NEW) } answers {
            entered.countDown()
            try {
                release.await()
                20L
            } catch (ex: InterruptedException) {
                interrupted.countDown()
                throw ex
            }
        }
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(2)) {
                collector.start()
                collector.start()
            }
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(collector.isRunning).isTrue()
            assertTimeoutPreemptively(Duration.ofSeconds(2)) { collector.stop() }
            assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(collector.isRunning).isFalse()
            verify(exactly = 1) { recordRepository.countByStatus(NEW) }
        } finally {
            release.countDown()
            collector.stop()
        }
    }

    @Test
    fun `concurrent refresh is skipped while a previous query remains blocked`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        every { recordRepository.countByStatus(NEW) } answers {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            15L
        }

        val firstRefresh = worker.submit { collector.refresh() }
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
            assertTimeoutPreemptively(Duration.ofSeconds(2)) { collector.refresh() }
            verify(exactly = 1) { recordRepository.countByStatus(NEW) }
        } finally {
            release.countDown()
            firstRefresh.get(5, TimeUnit.SECONDS)
            worker.shutdownNow()
        }

        collector.refresh()
        assertThat(recordGauge(NEW)).isEqualTo(15.0)
        verify(exactly = 2) { recordRepository.countByStatus(NEW) }
    }

    @Test
    fun `scheduled collection recovers automatically after a failed query`() {
        val attempts = AtomicInteger()
        val recovered = CountDownLatch(1)
        every { recordRepository.countByStatus(NEW) } answers {
            if (attempts.incrementAndGet() == 1) throw IllegalStateException("temporary database failure")
            20L
        }
        every { recordRepository.countByStatus(FAILED) } answers {
            if (attempts.get() >= 2) recovered.countDown()
            3L
        }
        val scheduledCollector =
            OutboxMetricsCollector(
                recordRepository,
                coordinator,
                instanceRegistry,
                OutboxMetricsProperties(refreshInterval = Duration.ofMillis(10)),
                clock,
                taskScheduler,
            )

        try {
            OutboxInstanceMetricsMeterBinder(scheduledCollector, OutboxChannelNameProvider.DEFAULT).bindTo(registry)
            scheduledCollector.start()
            assertThat(recovered.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(scheduledCollector.recordCount(NEW)).isEqualTo(20.0)
        } finally {
            scheduledCollector.stop()
        }
    }

    @Test
    fun `age gauges cache fractional seconds with only the channel tag`() {
        every { recordRepository.findOldestCreatedAtByPartitions(setOf(1, 2), NEW) } returns
            now.get().minusMillis(90_500)
        every { recordRepository.findOldestDueNextRetryAtByPartitions(setOf(1, 2), NEW, now.get(), true) } returns
            now.get().minusSeconds(30)
        collector.refresh()
        now.set(now.get().plusSeconds(10))

        assertThat(value(OutboxMetricNames.INSTANCE_RECORDS_OLDEST_AGE)).isEqualTo(90.5)
        assertThat(value(OutboxMetricNames.INSTANCE_RECORDS_OVERDUE_AGE)).isEqualTo(30.0)
        listOf(
            OutboxMetricNames.INSTANCE_RECORDS_OLDEST_AGE,
            OutboxMetricNames.INSTANCE_RECORDS_OVERDUE_AGE,
        ).forEach { name ->
            val gauge = registry.get(name).gauge()
            assertThat(gauge.id.baseUnit).isEqualTo("seconds")
            assertThat(gauge.id.tags.map { it.key to it.value })
                .containsExactly(OutboxMetricKeyNames.LowCardinality.CHANNEL to "orders")
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `overdue collection uses the configured stop on first failure setting`(stopOnFirstFailure: Boolean) {
        createCollector(stopOnFirstFailure = stopOnFirstFailure).refresh()

        verify(exactly = 1) {
            recordRepository.findOldestDueNextRetryAtByPartitions(setOf(1, 2), NEW, now.get(), stopOnFirstFailure)
        }
    }

    @Test
    fun `age collection refreshes assignments and skips all record queries when no partitions are assigned`() {
        every { recordRepository.findOldestCreatedAtByPartitions(setOf(1, 2), NEW) } returns now.get().minusSeconds(90)
        collector.refresh()
        every { coordinator.getAssignedPartitionNumbers() } returns setOf(3)
        every { recordRepository.countRecordsByPartitions(setOf(3), NEW) } returns 1
        every { recordRepository.findOldestCreatedAtByPartitions(setOf(3), NEW) } returns now.get().minusSeconds(20)
        collector.refresh()
        assertThat(value(OutboxMetricNames.INSTANCE_RECORDS_OLDEST_AGE)).isEqualTo(20.0)
        verify(exactly = 1) { recordRepository.findOldestDueNextRetryAtByPartitions(setOf(3), NEW, now.get(), true) }

        every { coordinator.getAssignedPartitionNumbers() } returns emptySet()
        collector.refresh()
        assertThat(value(OutboxMetricNames.INSTANCE_RECORDS_OLDEST_AGE)).isZero()
        assertThat(value(OutboxMetricNames.INSTANCE_RECORDS_OVERDUE_AGE)).isZero()
        verify(exactly = 2) { recordRepository.countRecordsByPartitions(any(), any()) }
        verify(exactly = 2) { recordRepository.findOldestCreatedAtByPartitions(any(), any()) }
        verify(exactly = 2) { recordRepository.findOldestDueNextRetryAtByPartitions(any(), any(), any(), any()) }
    }

    @Test
    fun `age gauges report zero for empty results and future creation times`() {
        collector.refresh()
        assertThat(value(OutboxMetricNames.INSTANCE_RECORDS_OLDEST_AGE)).isZero()
        assertThat(value(OutboxMetricNames.INSTANCE_RECORDS_OVERDUE_AGE)).isZero()

        every { recordRepository.findOldestCreatedAtByPartitions(any(), NEW) } returns now.get().plusSeconds(5)
        collector.refresh()
        assertThat(value(OutboxMetricNames.INSTANCE_RECORDS_OLDEST_AGE)).isZero()
    }

    @Test
    fun `age query failures are independent and prevent advancing the success timestamp until recovery`() {
        collector.refresh()
        val lastSuccess = collector.lastSuccessTimestamp
        now.set(now.get().plusSeconds(15))
        every { recordRepository.findOldestCreatedAtByPartitions(any(), NEW) } throws
            IllegalStateException("database unavailable")
        every { recordRepository.findOldestDueNextRetryAtByPartitions(any(), NEW, any(), any()) } returns
            now.get().minusSeconds(30)
        collector.refresh()
        assertThat(value(OutboxMetricNames.INSTANCE_RECORDS_OLDEST_AGE)).isNaN()
        assertThat(value(OutboxMetricNames.INSTANCE_RECORDS_OVERDUE_AGE)).isEqualTo(30.0)
        assertThat(value(OutboxMetricNames.INSTANCE_RECORDS_PENDING)).isEqualTo(10.0)
        assertThat(collector.lastSuccessTimestamp).isEqualTo(lastSuccess)

        every { recordRepository.findOldestCreatedAtByPartitions(any(), NEW) } returns now.get().minusSeconds(90)
        every { recordRepository.findOldestDueNextRetryAtByPartitions(any(), NEW, any(), any()) } throws
            IllegalStateException()
        collector.refresh()
        assertThat(value(OutboxMetricNames.INSTANCE_RECORDS_OLDEST_AGE)).isEqualTo(90.0)
        assertThat(value(OutboxMetricNames.INSTANCE_RECORDS_OVERDUE_AGE)).isNaN()
        assertThat(collector.lastSuccessTimestamp).isEqualTo(lastSuccess)

        every { recordRepository.findOldestDueNextRetryAtByPartitions(any(), NEW, any(), any()) } returns null
        collector.refresh()
        assertThat(value(OutboxMetricNames.INSTANCE_RECORDS_OVERDUE_AGE)).isZero()
        assertThat(collector.lastSuccessTimestamp).isEqualTo(now.get().epochSecond.toDouble())
    }

    @Test
    fun `prometheus age scrapes remain responsive and expire while an age query is blocked`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        PrometheusMeterRegistry(PrometheusConfig.DEFAULT).useRegistry { prometheus ->
            binder.bindTo(prometheus)
            collector.refresh()
            every { recordRepository.findOldestCreatedAtByPartitions(any(), NEW) } answers {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                null
            }
            val refresh = worker.submit { collector.refresh() }
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
                now.set(now.get().plusSeconds(60))
                assertTimeoutPreemptively(Duration.ofSeconds(2)) {
                    val scrape = prometheus.scrape()
                    assertThat(
                        scrape,
                    ).contains("outbox_instance_records_oldest_age_seconds{outbox_channel=\"orders\"} NaN")
                    assertThat(
                        scrape,
                    ).contains("outbox_instance_records_overdue_age_seconds{outbox_channel=\"orders\"} NaN")
                }
            } finally {
                release.countDown()
                worker.shutdownNow()
                refresh.get(5, TimeUnit.SECONDS)
            }
        }
    }

    private fun <T : MeterRegistry> T.useRegistry(block: (T) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }

    private fun value(name: String) = registry.get(name).gauge().value()

    private fun recordGauge(status: OutboxRecordStatus) =
        registry
            .get(OutboxMetricNames.RECORDS)
            .tag(OutboxMetricKeyNames.LowCardinality.RECORD_STATUS, status.name.lowercase())
            .gauge()
            .value()
}
