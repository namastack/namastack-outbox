package io.namastack.outbox

import io.mockk.mockk
import io.namastack.outbox.partition.PartitionCoordinator
import io.namastack.outbox.retry.ExponentialBackoffRetryPolicy
import io.namastack.outbox.retry.FixedDelayRetryPolicy
import io.namastack.outbox.retry.JitteredRetryPolicy
import io.namastack.outbox.retry.OutboxRetryPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@DisplayName("OutboxCoreAutoConfiguration")
class OutboxCoreAutoConfigurationTest {
    private fun contextRunner() =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxCoreAutoConfiguration::class.java))

    @Nested
    @DisplayName("When @EnableOutbox annotation is present")
    inner class WithEnableOutboxAnnotation {
        @Test
        fun `creates all required beans with fixed retry policy`() {
            contextRunner()
                .withUserConfiguration(CompleteTestConfig::class.java)
                .withPropertyValues(
                    "outbox.retry.policy=fixed",
                    "outbox.retry.fixed.delay=100",
                    "outbox.instance.graceful-shutdown-timeout-seconds=1",
                ).run { context ->
                    assertThat(context).hasSingleBean(Clock::class.java)
                    assertThat(context).hasSingleBean(OutboxRetryPolicy::class.java)
                    assertThat(context.getBean(OutboxRetryPolicy::class.java))
                        .isInstanceOf(FixedDelayRetryPolicy::class.java)
                    assertThat(context).hasSingleBean(OutboxInstanceRegistry::class.java)
                    assertThat(context).hasSingleBean(PartitionCoordinator::class.java)
                    assertThat(context).hasSingleBean(OutboxProcessingScheduler::class.java)
                }
        }

        @Test
        fun `creates beans with exponential retry policy by default`() {
            contextRunner()
                .withUserConfiguration(CompleteTestConfig::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(OutboxRetryPolicy::class.java)
                    assertThat(context.getBean(OutboxRetryPolicy::class.java))
                        .isInstanceOf(ExponentialBackoffRetryPolicy::class.java)
                }
        }

        @Test
        fun `creates beans with jittered retry policy when configured`() {
            contextRunner()
                .withUserConfiguration(CompleteTestConfig::class.java)
                .withPropertyValues(
                    "outbox.retry.policy=jittered",
                    "outbox.retry.jittered.base-policy=fixed",
                    "outbox.retry.jittered.jitter=100",
                    "outbox.instance.graceful-shutdown-timeout-seconds=1",
                ).run { context ->
                    assertThat(context).hasSingleBean(OutboxRetryPolicy::class.java)
                    assertThat(context.getBean(OutboxRetryPolicy::class.java))
                        .isInstanceOf(JitteredRetryPolicy::class.java)
                }
        }
    }

    @Nested
    @DisplayName("OutboxRecordProcessor Bean")
    inner class OutboxRecordProcessorBean {
        @Test
        fun `creates scheduler when processor is provided`() {
            contextRunner()
                .withUserConfiguration(CompleteTestConfig::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(OutboxRecordProcessor::class.java)
                    assertThat(context).hasSingleBean(OutboxProcessingScheduler::class.java)
                }
        }

        @Test
        fun `fails when processor is missing`() {
            contextRunner()
                .withUserConfiguration(ConfigWithoutRecordProcessor::class.java)
                .run { context ->
                    assertThat(context).hasFailed()
                    assertThat(context.getStartupFailure())
                        .hasMessageContaining("OutboxRecordProcessor")
                }
        }
    }

    @Nested
    @DisplayName("OutboxRecordRepository Bean")
    inner class OutboxRecordRepositoryBean {
        @Test
        fun `creates beans when repository is provided`() {
            contextRunner()
                .withUserConfiguration(CompleteTestConfig::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(OutboxRecordRepository::class.java)
                    assertThat(context).hasSingleBean(OutboxProcessingScheduler::class.java)
                }
        }

        @Test
        fun `fails when repository is missing`() {
            contextRunner()
                .withUserConfiguration(ConfigWithoutRecordRepository::class.java)
                .run { context ->
                    assertThat(context).hasFailed()
                    assertThat(context.getStartupFailure())
                        .hasMessageContaining("OutboxRecordRepository")
                }
        }
    }

    @Nested
    @DisplayName("OutboxInstanceRepository Bean")
    inner class OutboxInstanceRepositoryBean {
        @Test
        fun `creates instance registry when repository is provided`() {
            contextRunner()
                .withUserConfiguration(CompleteTestConfig::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(OutboxInstanceRepository::class.java)
                    assertThat(context).hasSingleBean(OutboxInstanceRegistry::class.java)
                }
        }

        @Test
        fun `fails when repository is missing`() {
            contextRunner()
                .withUserConfiguration(ConfigWithoutInstanceRepository::class.java)
                .run { context ->
                    assertThat(context).hasFailed()
                    assertThat(context.getStartupFailure())
                        .hasMessageContaining("OutboxInstanceRepository")
                }
        }
    }

    @Nested
    @DisplayName("OutboxRetryPolicy Bean")
    inner class OutboxRetryPolicyBean {
        @Test
        fun `uses custom retry policy when provided`() {
            contextRunner()
                .withUserConfiguration(ConfigWithCustomRetryPolicy::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(OutboxRetryPolicy::class.java)
                    assertThat(context.getBean(OutboxRetryPolicy::class.java))
                        .isInstanceOf(FixedDelayRetryPolicy::class.java)
                }
        }

        @Test
        fun `creates default exponential policy when not provided`() {
            contextRunner()
                .withUserConfiguration(CompleteTestConfig::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(OutboxRetryPolicy::class.java)
                    assertThat(context.getBean(OutboxRetryPolicy::class.java))
                        .isInstanceOf(ExponentialBackoffRetryPolicy::class.java)
                }
        }
    }

    @Nested
    @DisplayName("OutboxEventMulticaster Bean")
    inner class OutboxEventMulticasterBean {
        @Test
        fun `creates multicaster when event serializer is available`() {
            contextRunner()
                .withUserConfiguration(CompleteTestConfig::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(OutboxEventMulticaster::class.java)
                }
        }

        @Test
        fun `fails when event serializer is missing`() {
            contextRunner()
                .withUserConfiguration(ConfigWithoutEventSerializer::class.java)
                .run { context ->
                    assertThat(context).hasFailed()
                    assertThat(context.getStartupFailure())
                        .hasMessageContaining("OutboxEventSerializer")
                }
        }

        @Test
        fun `uses auto-configured multicaster when no custom one provided`() {
            contextRunner()
                .withUserConfiguration(CompleteTestConfig::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(OutboxEventMulticaster::class.java)
                    val bean = context.getBean("applicationEventMulticaster")
                    assertThat(bean).isInstanceOf(OutboxEventMulticaster::class.java)
                }
        }
    }

    @Nested
    @DisplayName("Missing Dependencies")
    inner class MissingDependencies {
        @Test
        fun `fails when all core dependencies missing`() {
            contextRunner()
                .withUserConfiguration(ConfigWithoutRequiredBeans::class.java)
                .run { context ->
                    assertThat(context).hasFailed()
                }
        }
    }

    @Nested
    @DisplayName("Properties Configuration")
    inner class PropertiesConfiguration {
        @Test
        fun `applies custom poll interval`() {
            contextRunner()
                .withUserConfiguration(CompleteTestConfig::class.java)
                .withPropertyValues(
                    "outbox.poll-interval=5000",
                    "outbox.instance.graceful-shutdown-timeout-seconds=1",
                ).run { context ->
                    assertThat(context).hasNotFailed()
                    assertThat(context).hasSingleBean(OutboxProcessingScheduler::class.java)
                }
        }

        @Test
        fun `applies custom batch size`() {
            contextRunner()
                .withUserConfiguration(CompleteTestConfig::class.java)
                .withPropertyValues(
                    "outbox.batch-size=50",
                    "outbox.instance.graceful-shutdown-timeout-seconds=1",
                ).run { context ->
                    assertThat(context).hasNotFailed()
                    assertThat(context).hasSingleBean(OutboxProcessingScheduler::class.java)
                }
        }

        @Test
        fun `applies instance configuration properties`() {
            contextRunner()
                .withUserConfiguration(CompleteTestConfig::class.java)
                .withPropertyValues(
                    "outbox.instance.graceful-shutdown-timeout-seconds=30",
                    "outbox.instance.stale-instance-timeout-seconds=60",
                    "outbox.instance.heartbeat-interval-seconds=10",
                ).run { context ->
                    assertThat(context).hasNotFailed()
                    assertThat(context).hasSingleBean(OutboxInstanceRegistry::class.java)
                }
        }

        @Test
        fun `applies schema initialization configuration`() {
            contextRunner()
                .withUserConfiguration(CompleteTestConfig::class.java)
                .withPropertyValues(
                    "outbox.schema-initialization.enabled=false",
                    "outbox.instance.graceful-shutdown-timeout-seconds=1",
                ).run { context ->
                    assertThat(context).hasNotFailed()
                }
        }
    }

    @EnableOutbox
    @Configuration
    private class CompleteTestConfig {
        @Bean
        fun outboxRecordRepository(): OutboxRecordRepository = mockk(relaxed = true)

        @Bean
        fun outboxRecordProcessor(): OutboxRecordProcessor = mockk(relaxed = true)

        @Bean
        fun outboxInstanceRepository(): OutboxInstanceRepository = mockk(relaxed = true)

        @Bean
        fun outboxEventSerializer(): OutboxEventSerializer = mockk(relaxed = true)
    }

    @EnableOutbox
    @Configuration
    private class ConfigWithoutRequiredBeans {
        @Bean
        fun outboxRecordRepository(): OutboxRecordRepository = mockk(relaxed = true)

        @Bean
        fun outboxInstanceRepository(): OutboxInstanceRepository = mockk(relaxed = true)
    }

    @EnableOutbox
    @Configuration
    private class ConfigWithoutRecordProcessor {
        @Bean
        fun outboxRecordRepository(): OutboxRecordRepository = mockk(relaxed = true)

        @Bean
        fun outboxEventSerializer(): OutboxEventSerializer = mockk(relaxed = true)

        @Bean
        fun outboxInstanceRepository(): OutboxInstanceRepository = mockk(relaxed = true)
    }

    @EnableOutbox
    @Configuration
    private class ConfigWithCustomRetryPolicy {
        @Bean
        fun outboxRecordRepository(): OutboxRecordRepository = mockk(relaxed = true)

        @Bean
        fun outboxRecordProcessor(): OutboxRecordProcessor = mockk(relaxed = true)

        @Bean
        fun outboxInstanceRepository(): OutboxInstanceRepository = mockk(relaxed = true)

        @Bean
        fun outboxEventSerializer(): OutboxEventSerializer = mockk(relaxed = true)

        @Bean
        fun retryPolicy(): OutboxRetryPolicy = FixedDelayRetryPolicy(java.time.Duration.ofSeconds(1))
    }

    @EnableOutbox
    @Configuration
    private class ConfigWithoutRecordRepository {
        @Bean
        fun outboxRecordProcessor(): OutboxRecordProcessor = mockk(relaxed = true)

        @Bean
        fun outboxEventSerializer(): OutboxEventSerializer = mockk(relaxed = true)

        @Bean
        fun outboxInstanceRepository(): OutboxInstanceRepository = mockk(relaxed = true)
    }

    @EnableOutbox
    @Configuration
    private class ConfigWithoutInstanceRepository {
        @Bean
        fun outboxRecordRepository(): OutboxRecordRepository = mockk(relaxed = true)

        @Bean
        fun outboxRecordProcessor(): OutboxRecordProcessor = mockk(relaxed = true)

        @Bean
        fun outboxEventSerializer(): OutboxEventSerializer = mockk(relaxed = true)
    }

    @EnableOutbox
    @Configuration
    private class ConfigWithoutEventSerializer {
        @Bean
        fun outboxRecordRepository(): OutboxRecordRepository = mockk(relaxed = true)

        @Bean
        fun outboxRecordProcessor(): OutboxRecordProcessor = mockk(relaxed = true)

        @Bean
        fun outboxInstanceRepository(): OutboxInstanceRepository = mockk(relaxed = true)
    }
}
