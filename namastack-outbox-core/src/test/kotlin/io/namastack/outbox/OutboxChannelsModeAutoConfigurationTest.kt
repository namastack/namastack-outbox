package io.namastack.outbox

import io.namastack.outbox.OutboxRuntimeMode.CHANNELS
import io.namastack.outbox.context.OutboxContextCollector
import io.namastack.outbox.handler.OutboxHandlerBeanPostProcessor
import io.namastack.outbox.handler.OutboxHandlerInfrastructureFactory
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
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import java.time.Clock

class OutboxChannelsModeAutoConfigurationTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(TestApplication::class.java)
            .withBean("channelsRuntimeModeProvider", OutboxRuntimeModeProvider::class.java, {
                OutboxRuntimeModeProvider { CHANNELS }
            })
            .withPropertyValues("namastack.outbox.mode=channels")

    @Test
    fun `keeps shared infrastructure and omits single runtime assembly`() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()

            assertThat(context).hasSingleBean(OutboxProperties::class.java)
            assertThat(context).hasSingleBean(Clock::class.java)
            assertThat(context).hasSingleBean(OutboxChannelNameProvider::class.java)
            assertThat(context).hasSingleBean(OutboxContextCollector::class.java)
            assertThat(context).hasSingleBean(OutboxHandlerInfrastructureFactory::class.java)

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

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    internal class TestApplication
}
