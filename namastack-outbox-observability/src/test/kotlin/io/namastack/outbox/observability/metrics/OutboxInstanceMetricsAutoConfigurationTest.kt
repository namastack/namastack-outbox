package io.namastack.outbox.observability.metrics

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.ObservationRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.OutboxChannelNameProvider
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.OutboxRecordStatus.NEW
import io.namastack.outbox.instance.OutboxInstanceRegistry
import io.namastack.outbox.observability.MicrometerOutboxInstrumentation
import io.namastack.outbox.observability.OutboxMetricNames
import io.namastack.outbox.observability.OutboxObservabilityAutoConfiguration
import io.namastack.outbox.partition.PartitionCoordinator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.TaskScheduler
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@DisplayName("OutboxInstanceMetricsAutoConfiguration")
class OutboxInstanceMetricsAutoConfigurationTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxInstanceMetricsAutoConfiguration::class.java))
            .withUserConfiguration(ChannelProviderConfig::class.java)

    @Nested
    @DisplayName("Bean Creation")
    inner class BeanCreation {
        @Test
        fun `creates meter binder when required beans exist`() {
            contextRunner
                .withUserConfiguration(RequiredBeansConfig::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(OutboxInstanceMetricsMeterBinder::class.java)
                }
        }

        @Test
        fun `backs off when custom meter binder exists`() {
            contextRunner
                .withUserConfiguration(RequiredBeansConfig::class.java, CustomMeterBinderConfig::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(OutboxInstanceMetricsMeterBinder::class.java)
                    assertThat(context).hasBean("customMeterBinder")
                    assertThat(context).doesNotHaveBean("outboxInstanceMetricsMeterBinder")
                    assertThat(context).doesNotHaveBean(OutboxMetricsCollector::class.java)
                }
        }
    }

    @Nested
    @DisplayName("Required Dependencies")
    inner class RequiredDependencies {
        @Test
        fun `does not create meter binder when record repository is missing`() {
            contextRunner
                .withUserConfiguration(MissingRecordRepositoryConfig::class.java)
                .run { context ->
                    assertThat(context).doesNotHaveBean(OutboxInstanceMetricsMeterBinder::class.java)
                    assertThat(context).doesNotHaveBean(OutboxMetricsCollector::class.java)
                }
        }

        @Test
        fun `does not create meter binder when partition coordinator is missing`() {
            contextRunner
                .withUserConfiguration(MissingPartitionCoordinatorConfig::class.java)
                .run { context ->
                    assertThat(context).doesNotHaveBean(OutboxInstanceMetricsMeterBinder::class.java)
                    assertThat(context).doesNotHaveBean(OutboxMetricsCollector::class.java)
                }
        }

        @Test
        fun `does not create meter binder when instance registry is missing`() {
            contextRunner
                .withUserConfiguration(MissingInstanceRegistryConfig::class.java)
                .run { context ->
                    assertThat(context).doesNotHaveBean(OutboxInstanceMetricsMeterBinder::class.java)
                    assertThat(context).doesNotHaveBean(OutboxMetricsCollector::class.java)
                }
        }
    }

    @Nested
    @DisplayName("Conditional Properties")
    inner class ConditionalProperties {
        @Test
        fun `does not create meter binder when outbox is disabled`() {
            contextRunner
                .withUserConfiguration(RequiredBeansConfig::class.java)
                .withPropertyValues("namastack.outbox.enabled=false")
                .run { context ->
                    assertThat(context).doesNotHaveBean(OutboxInstanceMetricsMeterBinder::class.java)
                    assertThat(context).doesNotHaveBean(OutboxMetricsCollector::class.java)
                }
        }
    }

    @Test
    fun `disabled state metrics create neither collector nor scheduler nor binder`() {
        contextRunner
            .withConfiguration(AutoConfigurations.of(OutboxObservabilityAutoConfiguration::class.java))
            .withBean(ObservationRegistry::class.java, { ObservationRegistry.create() })
            .withUserConfiguration(RequiredBeansConfig::class.java)
            .withPropertyValues("namastack.outbox.observability.metrics.enabled=false")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(OutboxInstanceMetricsMeterBinder::class.java)
                assertThat(context).doesNotHaveBean(OutboxMetricsCollector::class.java)
                assertThat(context).doesNotHaveBean(OutboxMetricsCollector.SCHEDULER_NAME)
                assertThat(context).hasSingleBean(MicrometerOutboxInstrumentation::class.java)
            }
    }

    @Test
    fun `tracing without bound gauges does not schedule collection even after context close`() {
        val scheduler = mockk<TaskScheduler>()
        var collector: OutboxMetricsCollector? = null
        var binder: OutboxInstanceMetricsMeterBinder? = null
        contextRunner
            .withConfiguration(AutoConfigurations.of(OutboxObservabilityAutoConfiguration::class.java))
            .withBean(ObservationRegistry::class.java, { ObservationRegistry.create() })
            .withBean(OutboxMetricsCollector.SCHEDULER_NAME, TaskScheduler::class.java, { scheduler })
            .withUserConfiguration(RequiredBeansConfig::class.java)
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(MeterRegistry::class.java)
                assertThat(context).hasSingleBean(MicrometerOutboxInstrumentation::class.java)
                collector = context.getBean(OutboxMetricsCollector::class.java)
                binder = context.getBean(OutboxInstanceMetricsMeterBinder::class.java)
                verify(exactly = 0) { scheduler.scheduleWithFixedDelay(any<Runnable>(), any<Duration>()) }
            }
        assertThat(collector!!.isRunning).isFalse()
        val registry = SimpleMeterRegistry()
        try {
            binder!!.bindTo(registry)
        } finally {
            registry.close()
        }
        verify(exactly = 0) { scheduler.scheduleWithFixedDelay(any<Runnable>(), any<Duration>()) }
    }

    @Test
    fun `binds collection settings and stops collector when context closes`() {
        var collector: OutboxMetricsCollector? = null
        contextRunner
            .withUserConfiguration(RequiredBeansConfig::class.java)
            .withPropertyValues(
                "namastack.outbox.observability.metrics.refresh-interval=2s",
                "namastack.outbox.observability.metrics.max-age=10s",
            ).run { context ->
                assertThat(context).hasNotFailed()
                val properties = context.getBean(OutboxMetricsProperties::class.java)
                assertThat(properties.refreshInterval).isEqualTo(Duration.ofSeconds(2))
                assertThat(properties.maxAge).isEqualTo(Duration.ofSeconds(10))
                collector = context.getBean(OutboxMetricsCollector::class.java)
                assertThat(collector.isRunning).isTrue()
            }
        assertThat(collector!!.isRunning).isFalse()
    }

    @Test
    fun `context closes promptly when a metrics query ignores interruption`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closer = Executors.newSingleThreadExecutor()
        val registry = SimpleMeterRegistry()
        val repository = mockk<OutboxRecordRepository>()
        every { repository.countByStatus(any()) } answers {
            entered.countDown()
            var released = false
            while (!released) {
                try {
                    released = release.await(10, TimeUnit.SECONDS)
                    check(released) { "Test did not release the database query" }
                } catch (_: InterruptedException) {
                    // Model a driver that does not cancel its database operation on interruption.
                }
            }
            0L
        }

        try {
            contextRunner
                .withUserConfiguration(MissingRecordRepositoryConfig::class.java)
                .withBean(OutboxRecordRepository::class.java, { repository })
                .run { context ->
                    assertThat(context).hasNotFailed()
                    context.getBean(OutboxInstanceMetricsMeterBinder::class.java).bindTo(registry)
                    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
                    val closed = closer.submit { context.sourceApplicationContext.close() }
                    try {
                        closed.get(2, TimeUnit.SECONDS)
                    } finally {
                        release.countDown()
                        closed.get(5, TimeUnit.SECONDS)
                    }
                }
        } finally {
            release.countDown()
            closer.shutdownNow()
            registry.close()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `age collector uses the configured clock and stop on first failure setting`(stopOnFirstFailure: Boolean) {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val scheduler = mockk<TaskScheduler>(relaxed = true)
        val registry = SimpleMeterRegistry()
        try {
            contextRunner
                .withUserConfiguration(RequiredBeansConfig::class.java)
                .withBean(Clock::class.java, { Clock.fixed(now, ZoneOffset.UTC) })
                .withBean(OutboxMetricsCollector.SCHEDULER_NAME, TaskScheduler::class.java, { scheduler })
                .withPropertyValues("namastack.outbox.processing.stop-on-first-failure=$stopOnFirstFailure")
                .run { context ->
                    assertThat(context).hasNotFailed()
                    val repository = context.getBean(OutboxRecordRepository::class.java)
                    every { context.getBean(PartitionCoordinator::class.java).getAssignedPartitionNumbers() } returns
                        setOf(1)
                    every { repository.countRecordsByPartitions(setOf(1), NEW) } returns 1
                    every { repository.findOldestCreatedAtByPartitions(setOf(1), NEW) } returns now.minusSeconds(90)
                    every {
                        repository.findOldestDueNextRetryAtByPartitions(setOf(1), NEW, now, stopOnFirstFailure)
                    } returns now.minusSeconds(30)
                    context.getBean(OutboxInstanceMetricsMeterBinder::class.java).bindTo(registry)
                    context.getBean(OutboxMetricsCollector::class.java).refresh()

                    assertThat(
                        registry.get(OutboxMetricNames.INSTANCE_RECORDS_OLDEST_AGE).gauge().value(),
                    ).isEqualTo(90.0)
                    assertThat(
                        registry.get(OutboxMetricNames.INSTANCE_RECORDS_OVERDUE_AGE).gauge().value(),
                    ).isEqualTo(30.0)
                    verify(exactly = 1) {
                        repository.findOldestDueNextRetryAtByPartitions(setOf(1), NEW, now, stopOnFirstFailure)
                    }
                }
        } finally {
            registry.close()
        }
    }

    @Test
    fun `rejects invalid collection settings`() {
        listOf(
            arrayOf("namastack.outbox.observability.metrics.refresh-interval=0s"),
            arrayOf("namastack.outbox.observability.metrics.refresh-interval=-1s"),
            arrayOf("namastack.outbox.observability.metrics.max-age=1s"),
        ).forEach { properties ->
            contextRunner
                .withUserConfiguration(RequiredBeansConfig::class.java)
                .withPropertyValues(*properties)
                .run { context -> assertThat(context).hasFailed() }
        }
    }

    @Configuration
    class ChannelProviderConfig {
        @Bean
        fun outboxChannelNameProvider(): OutboxChannelNameProvider = OutboxChannelNameProvider.DEFAULT
    }

    @Configuration
    class RequiredBeansConfig {
        @Bean
        fun outboxRecordRepository(): OutboxRecordRepository = mockk()

        @Bean
        fun partitionCoordinator(): PartitionCoordinator = mockk(relaxed = true)

        @Bean
        fun outboxInstanceRegistry(): OutboxInstanceRegistry = mockk(relaxed = true)
    }

    @Configuration
    class CustomMeterBinderConfig {
        @Bean
        fun customMeterBinder(): OutboxInstanceMetricsMeterBinder = mockk()
    }

    @Configuration
    class MissingRecordRepositoryConfig {
        @Bean
        fun partitionCoordinator(): PartitionCoordinator = mockk(relaxed = true)

        @Bean
        fun outboxInstanceRegistry(): OutboxInstanceRegistry = mockk(relaxed = true)
    }

    @Configuration
    class MissingPartitionCoordinatorConfig {
        @Bean
        fun outboxRecordRepository(): OutboxRecordRepository = mockk()

        @Bean
        fun outboxInstanceRegistry(): OutboxInstanceRegistry = mockk(relaxed = true)
    }

    @Configuration
    class MissingInstanceRegistryConfig {
        @Bean
        fun outboxRecordRepository(): OutboxRecordRepository = mockk()

        @Bean
        fun partitionCoordinator(): PartitionCoordinator = mockk(relaxed = true)
    }
}
