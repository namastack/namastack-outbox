package com.beisel.springoutbox

import com.beisel.springoutbox.lock.OutboxLockManager
import com.beisel.springoutbox.lock.OutboxLockRepository
import com.beisel.springoutbox.retry.ExponentialBackoffRetryPolicy
import com.beisel.springoutbox.retry.FixedDelayRetryPolicy
import com.beisel.springoutbox.retry.OutboxRetryPolicy
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

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
                    "outbox.retry.initial-delay=100",
                    "outbox.retry.max-delay=1000",
                ).run { context ->
                    assertThat(context).hasSingleBean(Clock::class.java)
                    assertThat(context).hasSingleBean(OutboxLockManager::class.java)
                    assertThat(context).hasSingleBean(OutboxRetryPolicy::class.java)
                    assertThat(context.getBean(OutboxRetryPolicy::class.java))
                        .isInstanceOf(FixedDelayRetryPolicy::class.java)
                    assertThat(context).hasBean("outboxScheduler")
                }
        }

        @Test
        fun `creates scheduler with all dependencies when all required beans are present`() {
            contextRunner()
                .withUserConfiguration(CompleteTestConfig::class.java)
                .withPropertyValues(
                    "outbox.retry.policy=exponential",
                    "outbox.retry.initial-delay=1000",
                    "outbox.retry.max-delay=60000",
                    "outbox.locking.extension-seconds=300",
                    "outbox.locking.refresh-threshold=60",
                ).run { context ->
                    assertThat(context).hasBean("outboxScheduler")
                    val scheduler = context.getBean("outboxScheduler", OutboxProcessingScheduler::class.java)
                    assertThat(scheduler).isNotNull
                }
        }

        @Test
        fun `does not create scheduler when OutboxRecordRepository is missing`() {
            contextRunner()
                .withUserConfiguration(ConfigWithoutRecordRepository::class.java)
                .withPropertyValues("outbox.retry.policy=fixed")
                .run { context ->
                    assertThat(context).hasSingleBean(Clock::class.java)
                    assertThat(context).hasSingleBean(OutboxLockManager::class.java)
                    assertThat(context).hasSingleBean(OutboxRetryPolicy::class.java)
                    assertThat(context).doesNotHaveBean("outboxScheduler")
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
                    "outbox.retry.initial-delay=500",
                    "outbox.retry.max-delay=30000",
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
                    "outbox.retry.initial-delay=200",
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
        fun `uses custom clock bean when provided`() {
            contextRunner()
                .withUserConfiguration(ConfigWithCustomClock::class.java)
                .withPropertyValues("outbox.retry.policy=fixed")
                .run { context ->
                    assertThat(context).hasSingleBean(Clock::class.java)
                    val clock = context.getBean(Clock::class.java)
                    assertThat(clock.instant()).isEqualTo(Instant.MAX)
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
                .withPropertyValues("outbox.retry.policy=fixed")
                .run { context ->
                    assertThat(context).doesNotHaveBean(Clock::class.java)
                    assertThat(context).doesNotHaveBean(OutboxLockManager::class.java)
                    assertThat(context).doesNotHaveBean(OutboxRetryPolicy::class.java)
                    assertThat(context).doesNotHaveBean("outboxScheduler")
                }
        }
    }

    // Test Configuration Classes

    @EnableOutbox
    @Configuration
    private class CompleteTestConfig {
        @Bean
        fun outboxLockRepository(): OutboxLockRepository = mockk(relaxed = true)

        @Bean
        fun outboxRecordRepository(): OutboxRecordRepository = mockk(relaxed = true)

        @Bean
        fun outboxRecordProcessor(): OutboxRecordProcessor = mockk(relaxed = true)
    }

    @EnableOutbox
    @Configuration
    private class ConfigWithoutRecordRepository {
        @Bean
        fun outboxLockRepository(): OutboxLockRepository = mockk(relaxed = true)

        @Bean
        fun outboxRecordProcessor(): OutboxRecordProcessor = mockk(relaxed = true)
    }

    @Configuration
    private class ConfigWithoutEnableOutbox {
        @Bean
        fun outboxLockRepository(): OutboxLockRepository = mockk(relaxed = true)

        @Bean
        fun outboxRecordRepository(): OutboxRecordRepository = mockk(relaxed = true)

        @Bean
        fun outboxRecordProcessor(): OutboxRecordProcessor = mockk(relaxed = true)
    }

    @EnableOutbox
    @Configuration
    private class ConfigWithCustomClock {
        @Bean
        fun outboxLockRepository(): OutboxLockRepository = mockk(relaxed = true)

        @Bean
        fun outboxRecordRepository(): OutboxRecordRepository = mockk(relaxed = true)

        @Bean
        fun outboxRecordProcessor(): OutboxRecordProcessor = mockk(relaxed = true)

        @Bean
        fun clock(): Clock = Clock.fixed(Instant.MAX, ZoneId.systemDefault())
    }
}
