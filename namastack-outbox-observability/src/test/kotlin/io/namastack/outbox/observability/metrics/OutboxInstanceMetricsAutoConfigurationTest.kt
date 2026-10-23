package io.namastack.outbox.observability.metrics

import io.micrometer.observation.ObservationRegistry
import io.mockk.every
import io.mockk.mockk
import io.namastack.outbox.OutboxChannelNameProvider
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.OutboxRecordStatusRepository
import io.namastack.outbox.instance.OutboxInstanceRegistry
import io.namastack.outbox.observability.MicrometerOutboxInstrumentation
import io.namastack.outbox.observability.OutboxObservabilityAutoConfiguration
import io.namastack.outbox.partition.PartitionCoordinator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration
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
                .withUserConfiguration(RequiredBeansConfig::class.java, StatusRepositoryConfig::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(OutboxInstanceMetricsMeterBinder::class.java)
                }
        }

        @Test
        fun `creates meter binder when status repository is missing`() {
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
        val repository = mockk<OutboxRecordStatusRepository>()
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
                .withUserConfiguration(RequiredBeansConfig::class.java)
                .withBean(OutboxRecordStatusRepository::class.java, { repository })
                .run { context ->
                    assertThat(context).hasNotFailed()
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
    class StatusRepositoryConfig {
        @Bean
        fun outboxRecordStatusRepository(): OutboxRecordStatusRepository = mockk()
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
