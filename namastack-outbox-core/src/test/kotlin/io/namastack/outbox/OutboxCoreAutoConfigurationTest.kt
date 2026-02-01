package io.namastack.outbox

import io.mockk.mockk
import io.namastack.outbox.instance.OutboxInstanceRegistry
import io.namastack.outbox.instance.OutboxInstanceRepository
import io.namastack.outbox.partition.PartitionAssignmentRepository
import io.namastack.outbox.retry.OutboxRetryPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledForJreRange
import org.junit.jupiter.api.condition.JRE
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.SimpleAsyncTaskExecutor
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.time.Clock
import java.time.Duration
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
            ).withPropertyValues("namastack.outbox.instance.graceful-shutdown-timeout-seconds=0")

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
                    val policy = context.getBean<OutboxRetryPolicy>()

                    // failureCount: 1 -> 2s, 2 -> 4s, 3 -> 8s, 4 -> 16s, 5 -> 32s, 6 -> 60s (cap)
                    assertThat(policy.nextDelay(1)).isEqualTo(Duration.ofSeconds(2))
                    assertThat(policy.nextDelay(2)).isEqualTo(Duration.ofSeconds(4))
                    assertThat(policy.nextDelay(3)).isEqualTo(Duration.ofSeconds(8))
                    assertThat(policy.nextDelay(4)).isEqualTo(Duration.ofSeconds(16))
                    assertThat(policy.nextDelay(5)).isEqualTo(Duration.ofSeconds(32))
                    assertThat(policy.nextDelay(6)).isEqualTo(Duration.ofSeconds(60))
                }
        }

        @Test
        fun `creates fixed retry policy when configured`() {
            contextRunner
                .withUserConfiguration(MinimalTestConfig::class.java)
                .withPropertyValues(
                    "namastack.outbox.retry.policy=fixed",
                    "namastack.outbox.retry.fixed.delay=2000",
                ).run { context ->
                    assertThat(context).hasSingleBean(OutboxRetryPolicy::class.java)
                    val policy = context.getBean<OutboxRetryPolicy>()

                    // failureCount: 1 -> 2s, 2 -> 2s, 3 -> 2s
                    assertThat(policy.nextDelay(1)).isEqualTo(Duration.ofSeconds(2))
                    assertThat(policy.nextDelay(2)).isEqualTo(Duration.ofSeconds(2))
                    assertThat(policy.nextDelay(3)).isEqualTo(Duration.ofSeconds(2))
                }
        }

        @Test
        fun `uses custom default retry policy when provided`() {
            contextRunner
                .withUserConfiguration(ConfigWithCustomRetryPolicy::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(OutboxRetryPolicy::class.java)
                    val policy = context.getBean<OutboxRetryPolicy>()

                    // failureCount: 1 -> 1s, 2 -> 1s, 3 -> 1s
                    assertThat(policy.nextDelay(1)).isEqualTo(Duration.ofSeconds(1))
                    assertThat(policy.nextDelay(2)).isEqualTo(Duration.ofSeconds(1))
                    assertThat(policy.nextDelay(3)).isEqualTo(Duration.ofSeconds(1))
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
                    "namastack.outbox.poll-interval=5000",
                    "namastack.outbox.batch-size=50",
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
                    "namastack.outbox.instance.graceful-shutdown-timeout-seconds=0",
                    "namastack.outbox.instance.stale-instance-timeout-seconds=1",
                    "namastack.outbox.instance.heartbeat-interval-seconds=1",
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
                    val executor = context.getBean("outboxTaskExecutor")

                    assertThat(executor)
                        .isInstanceOfSatisfying(ThreadPoolTaskExecutor::class.java) {
                            assertThat(it.corePoolSize).isEqualTo(4)
                            assertThat(it.maxPoolSize).isEqualTo(8)
                            assertThat(it.threadNamePrefix).isEqualTo("outbox-proc-")
                        }
                }
        }

        @Test
        fun `applies custom pool sizes from properties`() {
            contextRunner
                .withUserConfiguration(MinimalTestConfig::class.java)
                .withPropertyValues(
                    "namastack.outbox.processing.executor-core-pool-size=7",
                    "namastack.outbox.processing.executor-max-pool-size=15",
                ).run { context ->
                    val executor = context.getBean("outboxTaskExecutor")

                    assertThat(executor)
                        .isInstanceOfSatisfying(ThreadPoolTaskExecutor::class.java) {
                            assertThat(it.corePoolSize).isEqualTo(7)
                            assertThat(it.maxPoolSize).isEqualTo(15)
                        }
                }
        }

        @Test
        @EnabledForJreRange(min = JRE.JAVA_21)
        fun `creates default outboxTaskExecutor with virtual threads`() {
            contextRunner
                .withUserConfiguration(MinimalTestConfig::class.java)
                .withPropertyValues(
                    "spring.threads.virtual.enabled=true",
                ).run { context ->
                    val executor = context.getBean("outboxTaskExecutor")

                    assertThat(executor)
                        .isInstanceOfSatisfying(SimpleAsyncTaskExecutor::class.java) {
                            assertThat(it.concurrencyLimit).isEqualTo(-1)
                            assertThat(it.threadNamePrefix).isEqualTo("outbox-proc-")
                        }
                }
        }

        @Test
        @EnabledForJreRange(min = JRE.JAVA_21)
        fun `applies custom concurrency limit from properties`() {
            contextRunner
                .withUserConfiguration(MinimalTestConfig::class.java)
                .withPropertyValues(
                    "spring.threads.virtual.enabled=true",
                    "namastack.outbox.processing.executor-concurrency-limit=100",
                ).run { context ->
                    val executor = context.getBean("outboxTaskExecutor")

                    assertThat(executor)
                        .isInstanceOfSatisfying(SimpleAsyncTaskExecutor::class.java) {
                            assertThat(it.concurrencyLimit).isEqualTo(100)
                        }
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
                    val scheduler = context.getBean("outboxDefaultScheduler")

                    assertThat(scheduler)
                        .isInstanceOfSatisfying(ThreadPoolTaskScheduler::class.java) {
                            assertThat(it.scheduledThreadPoolExecutor.corePoolSize).isEqualTo(5)
                            assertThat(it.threadNamePrefix).isEqualTo("outbox-scheduler-")
                        }
                }
        }

        @Test
        @EnabledForJreRange(min = JRE.JAVA_21)
        fun `creates default outboxDefaultScheduler with virtual threads`() {
            contextRunner
                .withUserConfiguration(MinimalTestConfig::class.java)
                .withPropertyValues(
                    "spring.threads.virtual.enabled=true",
                ).run { context ->
                    val scheduler = context.getBean("outboxDefaultScheduler")

                    assertThat(scheduler)
                        .isInstanceOfSatisfying(SimpleAsyncTaskScheduler::class.java) {
                            assertThat(it.concurrencyLimit).isEqualTo(5)
                            assertThat(it.threadNamePrefix).isEqualTo("outbox-scheduler-")
                        }
                }
        }

        @Test
        fun `creates default outboxRebalancingScheduler with pool size 1`() {
            contextRunner
                .withUserConfiguration(MinimalTestConfig::class.java)
                .run { context ->
                    val scheduler = context.getBean("outboxRebalancingScheduler")

                    assertThat(scheduler)
                        .isInstanceOfSatisfying(ThreadPoolTaskScheduler::class.java) {
                            assertThat(it.scheduledThreadPoolExecutor.corePoolSize).isEqualTo(1)
                            assertThat(it.threadNamePrefix).isEqualTo("outbox-rebalancing-")
                        }
                }
        }

        @Test
        @EnabledForJreRange(min = JRE.JAVA_21)
        fun `creates default outboxRebalancingScheduler with virtual threads`() {
            contextRunner
                .withUserConfiguration(MinimalTestConfig::class.java)
                .withPropertyValues(
                    "spring.threads.virtual.enabled=true",
                ).run { context ->
                    val scheduler = context.getBean("outboxRebalancingScheduler")

                    assertThat(scheduler)
                        .isInstanceOfSatisfying(SimpleAsyncTaskScheduler::class.java) {
                            assertThat(it.concurrencyLimit).isEqualTo(1)
                            assertThat(it.threadNamePrefix).isEqualTo("outbox-rebalancing-")
                        }
                }
        }

        @Test
        fun `schedulers are singletons`() {
            contextRunner
                .withUserConfiguration(MinimalTestConfig::class.java)
                .run { context ->
                    val scheduler1 = context.getBean("outboxDefaultScheduler")
                    val scheduler2 = context.getBean("outboxDefaultScheduler")
                    assertThat(scheduler1).isSameAs(scheduler2)
                }
        }
    }

    @Configuration
    private class MinimalTestConfig {
        @Bean
        fun outboxRecordRepository() = mockk<OutboxRecordRepository>(relaxed = true)

        @Bean
        fun partitionAssignmentRepository() = mockk<PartitionAssignmentRepository>(relaxed = true)

        @Bean
        fun outboxInstanceRepository() = mockk<OutboxInstanceRepository>(relaxed = true)
    }

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

    @Configuration
    private class ConfigWithCustomRetryPolicy {
        @Bean
        fun outboxRecordRepository() = mockk<OutboxRecordRepository>(relaxed = true)

        @Bean
        fun partitionAssignmentRepository() = mockk<PartitionAssignmentRepository>(relaxed = true)

        @Bean
        fun outboxInstanceRepository() = mockk<OutboxInstanceRepository>(relaxed = true)

        @Bean
        fun outboxRetryPolicy() =
            OutboxRetryPolicy
                .builder()
                .maxRetries(maxRetries = 3)
                .fixedBackOff(delay = Duration.ofSeconds(1))
                .build()
    }

    @Configuration
    private class ConfigWithoutRepository {
        @Bean
        fun partitionAssignmentRepository() = mockk<PartitionAssignmentRepository>(relaxed = true)

        @Bean
        fun outboxInstanceRepository() = mockk<OutboxInstanceRepository>(relaxed = true)
    }
}
