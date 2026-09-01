package io.namastack.outbox

import io.namastack.outbox.OutboxRuntimeMode.CHANNELS
import io.namastack.outbox.config.OutboxCoreInfrastructureAutoConfiguration
import io.namastack.outbox.config.OutboxCoreMulticasterAutoConfiguration
import io.namastack.outbox.config.OutboxCoreProcessingAutoConfiguration
import io.namastack.outbox.config.OutboxCoreSchedulingAutoConfiguration
import io.namastack.outbox.config.OutboxCoreThreadingAutoConfiguration
import io.namastack.outbox.config.OutboxRuntimeModeAutoConfiguration
import io.namastack.outbox.context.OutboxContextCollector
import io.namastack.outbox.handler.OutboxHandlerBeanPostProcessor
import io.namastack.outbox.handler.invoker.OutboxFallbackHandlerInvoker
import io.namastack.outbox.handler.invoker.OutboxHandlerInvoker
import io.namastack.outbox.handler.registry.OutboxFallbackHandlerRegistry
import io.namastack.outbox.handler.registry.OutboxHandlerRegistry
import io.namastack.outbox.instance.OutboxInstanceRegistry
import io.namastack.outbox.partition.PartitionAssignmentCache
import io.namastack.outbox.partition.PartitionCoordinator
import io.namastack.outbox.processor.OutboxRecordProcessor
import io.namastack.outbox.retry.OutboxRetryPolicy
import io.namastack.outbox.retry.OutboxRetryPolicyRegistry
import io.namastack.outbox.trigger.OutboxPollingTrigger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Clock

class OutboxSingleRuntimeModeAutoConfigurationTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    TaskExecutionAutoConfiguration::class.java,
                    TaskSchedulingAutoConfiguration::class.java,
                    OutboxRuntimeModeAutoConfiguration::class.java,
                    OutboxCoreInfrastructureAutoConfiguration::class.java,
                    OutboxCoreThreadingAutoConfiguration::class.java,
                    OutboxCoreProcessingAutoConfiguration::class.java,
                    OutboxCoreSchedulingAutoConfiguration::class.java,
                    OutboxCoreMulticasterAutoConfiguration::class.java,
                ),
            )

    @Test
    fun `channels mode keeps shared infrastructure and omits single runtime graph`() {
        contextRunner
            .withBean("channelsRuntimeModeProvider", OutboxRuntimeModeProvider::class.java, {
                OutboxRuntimeModeProvider { CHANNELS }
            })
            .withPropertyValues("namastack.outbox.mode=channels")
            .run { context ->
                assertThat(context).hasNotFailed()

                assertThat(context).hasSingleBean(OutboxProperties::class.java)
                assertThat(context).hasSingleBean(Clock::class.java)
                assertThat(context).hasSingleBean(OutboxChannelNameProvider::class.java)
                assertThat(context).hasSingleBean(OutboxContextCollector::class.java)
                assertThat(context).doesNotHaveBean(Outbox::class.java)
                assertThat(context).doesNotHaveBean(OutboxRetryPolicy::class.java)
                assertThat(context).doesNotHaveBean(OutboxHandlerRegistry::class.java)
                assertThat(context).doesNotHaveBean(OutboxFallbackHandlerRegistry::class.java)
                assertThat(context).doesNotHaveBean(OutboxRetryPolicyRegistry::class.java)
                assertThat(context).doesNotHaveBean(OutboxHandlerBeanPostProcessor::class.java)
                assertThat(context).doesNotHaveBean(OutboxHandlerInvoker::class.java)
                assertThat(context).doesNotHaveBean(OutboxFallbackHandlerInvoker::class.java)
                assertThat(context).doesNotHaveBean(OutboxInstanceRegistry::class.java)
                assertThat(context).doesNotHaveBean(PartitionAssignmentCache::class.java)
                assertThat(context).doesNotHaveBean(PartitionCoordinator::class.java)
                assertThat(context).doesNotHaveBean(OutboxRecordProcessor::class.java)
                assertThat(context).doesNotHaveBean(OutboxPollingTrigger::class.java)
                assertThat(context).doesNotHaveBean(OutboxProcessingScheduler::class.java)
                assertThat(context).doesNotHaveBean(OutboxEventMulticaster::class.java)
                assertThat(context).doesNotHaveBean("outboxTaskExecutor")
                assertThat(context).doesNotHaveBean("outboxDefaultScheduler")
                assertThat(context).doesNotHaveBean("outboxHeartbeatScheduler")
            }
    }
}
