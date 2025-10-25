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
    @DisplayName("Retry Policy Configuration")
    inner class RetryPolicyConfiguration {
        @Test
        fun `provides exponential backoff retry policy when configured`() {
            contextRunner()
                .withUserConfiguration(CompleteTestConfig::class.java)
                .withPropertyValues(
                    "outbox.retry.policy=exponential",
                    "outbox.retry.exponential.initial-delay=500",
                    "outbox.retry.exponential.max-delay=30000",
                    "outbox.instance.graceful-shutdown-timeout-seconds=1",
                ).run { context ->
                    assertThat(context).hasSingleBean(OutboxRetryPolicy::class.java)
                    assertThat(context.getBean(OutboxRetryPolicy::class.java))
                        .isInstanceOf(ExponentialBackoffRetryPolicy::class.java)
                }
        }

        @Test
        fun `throws exception when unknown policy is configured`() {
            contextRunner()
                .withUserConfiguration(CompleteTestConfig::class.java)
                .withPropertyValues(
                    "outbox.retry.policy=unknown",
                    "outbox.instance.graceful-shutdown-timeout-seconds=1",
                ).run { context ->
                    assertThat(context).hasFailed()
                    assertThat(context.getStartupFailure())
                        .hasMessageContaining("Unsupported retry-policy: unknown")
                }
        }

        @Test
        fun `handles default property values correctly`() {
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
    @DisplayName("Bean Override Scenarios")
    inner class BeanOverrideScenarios {
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
        fun `throws exception when record processor is missing`() {
            contextRunner()
                .withUserConfiguration(ConfigWithoutRequiredBeans::class.java)
                .run { context ->
                    assertThat(context).hasFailed()
                    assertThat(context.getStartupFailure())
                        .hasMessageContaining("OutboxRecordProcessor")
                }
        }
    }

    @Nested
    @DisplayName("When @EnableOutbox annotation is missing")
    inner class WithoutEnableOutboxAnnotation {
        @Test
        fun `does not create any outbox beans`() {
            contextRunner()
                .withUserConfiguration(ConfigWithoutEnableOutbox::class.java)
                .withPropertyValues(
                    "outbox.retry.policy=fixed",
                    "outbox.instance.graceful-shutdown-timeout-seconds=1",
                ).run { context ->
                    assertThat(context).doesNotHaveBean(Clock::class.java)
                    assertThat(context).doesNotHaveBean(OutboxRetryPolicy::class.java)
                    assertThat(context).doesNotHaveBean(OutboxInstanceRegistry::class.java)
                    assertThat(context).doesNotHaveBean(PartitionCoordinator::class.java)
                    assertThat(context).doesNotHaveBean(OutboxProcessingScheduler::class.java)
                }
        }
    }

    @Nested
    @DisplayName("Conditional Bean Creation")
    inner class ConditionalBeanCreation {
        @Test
        fun `creates all beans when all dependencies are present`() {
            contextRunner()
                .withUserConfiguration(CompleteTestConfig::class.java)
                .run { context ->
                    assertThat(context).hasNotFailed()
                    assertThat(context).hasSingleBean(Clock::class.java)
                    assertThat(context).hasSingleBean(OutboxRetryPolicy::class.java)
                    assertThat(context).hasSingleBean(OutboxInstanceRegistry::class.java)
                    assertThat(context).hasSingleBean(PartitionCoordinator::class.java)
                    assertThat(context).hasSingleBean(OutboxProcessingScheduler::class.java)
                }
        }

        @Test
        fun `creates only basic beans when no dependencies provided`() {
            contextRunner()
                .withUserConfiguration(MinimalTestConfig::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(Clock::class.java)
                    assertThat(context).hasSingleBean(OutboxRetryPolicy::class.java)
                    assertThat(context).doesNotHaveBean(OutboxInstanceRegistry::class.java)
                    assertThat(context).doesNotHaveBean(PartitionCoordinator::class.java)
                    assertThat(context).doesNotHaveBean(OutboxProcessingScheduler::class.java)
                }
        }

        @Test
        fun `creates instance registry when instance repository available`() {
            contextRunner()
                .withUserConfiguration(ConfigWithOnlyInstanceRepository::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(Clock::class.java)
                    assertThat(context).hasSingleBean(OutboxRetryPolicy::class.java)
                    assertThat(context).hasSingleBean(OutboxInstanceRegistry::class.java)
                    assertThat(context).hasSingleBean(PartitionCoordinator::class.java)
                    assertThat(context).doesNotHaveBean(OutboxProcessingScheduler::class.java)
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
    }

    @EnableOutbox
    @Configuration
    private class MinimalTestConfig

    @EnableOutbox
    @Configuration
    private class ConfigWithOnlyInstanceRepository {
        @Bean
        fun outboxInstanceRepository(): OutboxInstanceRepository = mockk(relaxed = true)
    }

    @EnableOutbox
    @Configuration
    private class ConfigWithoutRequiredBeans {
        @Bean
        fun outboxRecordRepository(): OutboxRecordRepository = mockk(relaxed = true)

        @Bean
        fun outboxInstanceRepository(): OutboxInstanceRepository = mockk(relaxed = true)

        // Missing OutboxRecordProcessor - this should cause the partitionAwareOutboxProcessingScheduler to fail
    }

    @Configuration
    private class ConfigWithoutEnableOutbox {
        @Bean
        fun outboxRecordRepository(): OutboxRecordRepository = mockk(relaxed = true)

        @Bean
        fun outboxRecordProcessor(): OutboxRecordProcessor = mockk(relaxed = true)

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
        fun retryPolicy(): OutboxRetryPolicy = FixedDelayRetryPolicy(java.time.Duration.ofSeconds(1))
    }
}
