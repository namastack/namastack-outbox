package io.namastack.outbox.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.time.Duration

class JdbcOutboxCompletionAutoConfigurationTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JdbcOutboxCompletionAutoConfiguration::class.java))

    @Test
    fun `completion executor is absent by default`() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.containsBean("outboxCompletionExecutor")).isFalse()
        }
    }

    @Test
    fun `completion executor uses configured writer count when enabled`() {
        contextRunner
            .withPropertyValues(
                "namastack.outbox.jdbc.completion-batching.enabled=true",
                "namastack.outbox.jdbc.completion-batching.writer-count=3",
                "namastack.outbox.jdbc.completion-batching.max-batch-size=512",
                "namastack.outbox.jdbc.completion-batching.max-wait=2ms",
            ).run { context ->
                assertThat(context).hasNotFailed()
                val executor = context.getBean("outboxCompletionExecutor", ThreadPoolTaskExecutor::class.java)
                assertThat(executor.corePoolSize).isEqualTo(3)
                assertThat(executor.maxPoolSize).isEqualTo(3)

                val settings = context.getBean(JdbcOutboxConfigurationProperties::class.java).completionBatching
                assertThat(settings.maxBatchSize).isEqualTo(512)
                assertThat(settings.maxWait).isEqualTo(Duration.ofMillis(2))
            }
    }

    @Test
    fun `completion executor is absent when outbox is disabled`() {
        contextRunner
            .withPropertyValues(
                "namastack.outbox.enabled=false",
                "namastack.outbox.jdbc.completion-batching.enabled=true",
            ).run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.containsBean("outboxCompletionExecutor")).isFalse()
            }
    }

    @Test
    fun `completion executor is absent in channels mode`() {
        contextRunner
            .withPropertyValues(
                "namastack.outbox.mode=channels",
                "namastack.outbox.jdbc.completion-batching.enabled=true",
            ).run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.containsBean("outboxCompletionExecutor")).isFalse()
            }
    }
}
