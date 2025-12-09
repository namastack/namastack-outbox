package io.namastack.outbox

import io.mockk.mockk
import io.namastack.outbox.annotation.EnableOutbox
import io.namastack.outbox.instance.OutboxInstanceRegistry
import io.namastack.outbox.instance.OutboxInstanceRepository
import io.namastack.outbox.partition.PartitionAssignmentRepository
import io.namastack.outbox.retry.ExponentialBackoffRetryPolicy
import io.namastack.outbox.retry.FixedDelayRetryPolicy
import io.namastack.outbox.retry.JitteredRetryPolicy
import io.namastack.outbox.retry.OutboxRetryPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@DisplayName("OutboxCoreAutoConfiguration")
class OutboxCoreAutoConfigurationTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    TaskExecutionAutoConfiguration::class.java,
                    TaskSchedulingAutoConfiguration::class.java,
                    OutboxCoreAutoConfiguration::class.java,
                ),
            ).withPropertyValues("outbox.instance.graceful-shutdown-timeout-seconds=0")

    @Nested
    @DisplayName("Core Beans")
    inner class CoreBeans {
        @Test
        fun `creates default clock when none provided`() {
            contextRunner
                .withUserConfiguration(MinimalTestConfig::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(Clock::class.java)
                }
        }

        @Test
        fun `uses custom clock when provided`() {
            val expectedInstant = Instant.parse("2020-02-02T00:00:00Z")
            val expectedZone = ZoneId.systemDefault()

            contextRunner
                .withUserConfiguration(ConfigWithCustomClock::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(Clock::class.java)

                    val clock = context.getBean("clock") as Clock
                    assertThat(clock.instant()).isEqualTo(expectedInstant)
                    assertThat(clock.zone).isEqualTo(expectedZone)
                }
        }

        @Test
        fun `creates default instance registry when none provided`() {
            contextRunner
                .withUserConfiguration(MinimalTestConfig::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(OutboxInstanceRegistry::class.java)
                }
        }

        @Test
        fun `uses custom instance registry when provided`() {
            contextRunner
                .withUserConfiguration(ConfigWithCustomInstanceRegistry::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(OutboxInstanceRegistry::class.java)
                    assertThat(context.getBean(OutboxInstanceRegistry::class.java))
                        .isEqualTo(ConfigWithCustomInstanceRegistry.instanceRegistry)
                }
        }
    }

    @Nested
    @DisplayName("Retry Policy")
    inner class RetryPolicyConfiguration {
        @Test
        fun `creates exponential backoff policy by default`() {
            contextRunner
                .withUserConfiguration(MinimalTestConfig::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(OutboxRetryPolicy::class.java)
                    assertThat(context.getBean(OutboxRetryPolicy::class.java))
                        .isInstanceOf(ExponentialBackoffRetryPolicy::class.java)
                }
        }

        @Test
        fun `uses custom retry policy when provided`() {
            contextRunner
                .withUserConfiguration(ConfigWithCustomRetryPolicy::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(OutboxRetryPolicy::class.java)
                    assertThat(context.getBean(OutboxRetryPolicy::class.java))
                        .isInstanceOf(FixedDelayRetryPolicy::class.java)
                }
        }

        @Test
        fun `creates jittered retry policy when configured`() {
            contextRunner
                .withUserConfiguration(MinimalTestConfig::class.java)
                .withPropertyValues(
                    "outbox.retry.policy=jittered",
                    "outbox.retry.jittered.base-policy=fixed",
                    "outbox.retry.jittered.jitter=100",
                ).run { context ->
                    assertThat(context).hasSingleBean(OutboxRetryPolicy::class.java)
                    assertThat(context.getBean(OutboxRetryPolicy::class.java))
                        .isInstanceOf(JitteredRetryPolicy::class.java)
                }
        }
    }

    @Nested
    @DisplayName("Required Dependencies")
    inner class RequiredDependencies {
        @Nested
        @DisplayName("OutboxRecordRepository")
        inner class RepositoryDependency {
            @Test
            fun `creates scheduler when repository exists`() {
                contextRunner
                    .withUserConfiguration(MinimalTestConfig::class.java)
                    .run { context ->
                        assertThat(context).hasSingleBean(OutboxRecordRepository::class.java)
                        assertThat(context).hasSingleBean(OutboxProcessingScheduler::class.java)
                    }
            }

            @Test
            fun `fails when repository is missing`() {
                contextRunner
                    .withUserConfiguration(ConfigWithoutRepository::class.java)
                    .run { context ->
                        assertThat(context).hasFailed()
                        assertThat(context.startupFailure)
                            .hasMessageContaining("OutboxRecordRepository")
                    }
            }
        }
    }

    @Nested
    @DisplayName("Configuration Properties")
    inner class PropertyConfiguration {
        @Test
        fun `applies custom scheduling properties`() {
            contextRunner
                .withUserConfiguration(MinimalTestConfig::class.java)
                .withPropertyValues(
                    "outbox.poll-interval=5000",
                    "outbox.batch-size=50",
                ).run { context ->
                    assertThat(context).hasNotFailed()
                    assertThat(context).hasSingleBean(OutboxProcessingScheduler::class.java)
                }
        }

        @Test
        fun `applies instance configuration properties`() {
            contextRunner
                .withUserConfiguration(MinimalTestConfig::class.java)
                .withPropertyValues(
                    "outbox.instance.graceful-shutdown-timeout-seconds=0",
                    "outbox.instance.stale-instance-timeout-seconds=1",
                    "outbox.instance.heartbeat-interval-seconds=1",
                ).run { context ->
                    assertThat(context).hasNotFailed()
                    assertThat(context).hasSingleBean(OutboxInstanceRegistry::class.java)
                }
        }
    }

    @Nested
    @DisplayName("TaskExecutor Configuration")
    inner class TaskExecutorConfiguration {
        @Test
        fun `creates default outboxTaskExecutor with default pool sizes`() {
            contextRunner
                .withUserConfiguration(MinimalTestConfig::class.java)
                .run { context ->
                    val executor =
                        context.getBean(
                            "outboxTaskExecutor",
                        ) as org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
                    assertThat(executor.corePoolSize).isEqualTo(4)
                    assertThat(executor.maxPoolSize).isEqualTo(8)
                }
        }

        @Test
        fun `applies custom pool sizes from properties`() {
            contextRunner
                .withUserConfiguration(MinimalTestConfig::class.java)
                .withPropertyValues(
                    "outbox.processing.executor-core-pool-size=7",
                    "outbox.processing.executor-max-pool-size=15",
                ).run { context ->
                    val executor =
                        context.getBean(
                            "outboxTaskExecutor",
                        ) as org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
                    assertThat(executor.corePoolSize).isEqualTo(7)
                    assertThat(executor.maxPoolSize).isEqualTo(15)
                }
        }
    }

    @Nested
    @DisplayName("Scheduler Configuration")
    inner class SchedulerConfiguration {
        @Test
        fun `creates default outboxDefaultScheduler with pool size 5`() {
            contextRunner
                .withUserConfiguration(MinimalTestConfig::class.java)
                .run { context ->
                    val scheduler =
                        context.getBean(
                            "outboxDefaultScheduler",
                            ThreadPoolTaskScheduler::class.java,
                        )
                    assertThat(scheduler).isNotNull
                    assertThat(scheduler.scheduledThreadPoolExecutor.corePoolSize).isEqualTo(5)
                    assertThat(scheduler.threadNamePrefix).isEqualTo("outbox-scheduler-")
                }
        }

        @Test
        fun `creates default outboxRebalancingScheduler with pool size 1`() {
            contextRunner
                .withUserConfiguration(MinimalTestConfig::class.java)
                .run { context ->
                    val scheduler =
                        context.getBean(
                            "outboxRebalancingScheduler",
                            ThreadPoolTaskScheduler::class.java,
                        )
                    assertThat(scheduler).isNotNull
                    assertThat(scheduler.scheduledThreadPoolExecutor.corePoolSize).isEqualTo(1)
                    assertThat(scheduler.threadNamePrefix).isEqualTo("outbox-rebalancing-")
                }
        }

        @Test
        fun `schedulers are singletons`() {
            contextRunner
                .withUserConfiguration(MinimalTestConfig::class.java)
                .run { context ->
                    val scheduler1 =
                        context.getBean(
                            "outboxDefaultScheduler",
                            ThreadPoolTaskScheduler::class.java,
                        )
                    val scheduler2 =
                        context.getBean(
                            "outboxDefaultScheduler",
                            ThreadPoolTaskScheduler::class.java,
                        )
                    assertThat(scheduler1).isSameAs(scheduler2)
                }
        }
    }

    @EnableOutbox
    @Configuration
    private class MinimalTestConfig {
        @Bean
        fun outboxRecordRepository() = mockk<OutboxRecordRepository>(relaxed = true)

        @Bean
        fun partitionAssignmentRepository() = mockk<PartitionAssignmentRepository>(relaxed = true)

        @Bean
        fun outboxInstanceRepository() = mockk<OutboxInstanceRepository>(relaxed = true)
    }

    @EnableOutbox
    @Configuration
    private class ConfigWithCustomClock {
        @Bean
        fun clock(): Clock = Clock.fixed(Instant.parse("2020-02-02T00:00:00Z"), ZoneId.systemDefault())

        @Bean
        fun outboxRecordRepository() = mockk<OutboxRecordRepository>(relaxed = true)

        @Bean
        fun partitionAssignmentRepository() = mockk<PartitionAssignmentRepository>(relaxed = true)

        @Bean
        fun outboxInstanceRepository() = mockk<OutboxInstanceRepository>(relaxed = true)
    }

    @EnableOutbox
    @Configuration
    private class ConfigWithCustomInstanceRegistry {
        companion object {
            val instanceRegistry = mockk<OutboxInstanceRegistry>(relaxed = true)
        }

        @Bean
        fun outboxRecordRepository() = mockk<OutboxRecordRepository>(relaxed = true)

        @Bean
        fun partitionAssignmentRepository() = mockk<PartitionAssignmentRepository>(relaxed = true)

        @Bean
        fun outboxInstanceRepository() = mockk<OutboxInstanceRepository>(relaxed = true)

        @Bean
        fun outboxInstanceRegistry() = instanceRegistry
    }

    @EnableOutbox
    @Configuration
    private class ConfigWithCustomRetryPolicy {
        @Bean
        fun outboxRecordRepository() = mockk<OutboxRecordRepository>(relaxed = true)

        @Bean
        fun partitionAssignmentRepository() = mockk<PartitionAssignmentRepository>(relaxed = true)

        @Bean
        fun outboxInstanceRepository() = mockk<OutboxInstanceRepository>(relaxed = true)

        @Bean
        fun retryPolicy() = FixedDelayRetryPolicy(java.time.Duration.ofSeconds(1))
    }

    @EnableOutbox
    @Configuration
    private class ConfigWithoutRepository {
        @Bean
        fun partitionAssignmentRepository() = mockk<PartitionAssignmentRepository>(relaxed = true)

        @Bean
        fun outboxInstanceRepository() = mockk<OutboxInstanceRepository>(relaxed = true)
    }
}
