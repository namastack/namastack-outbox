package io.namastack.outbox.observability.metrics

import io.namastack.outbox.OutboxChannelNameProvider
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.OutboxRecordStatusRepository
import io.namastack.outbox.OutboxService
import io.namastack.outbox.config.ConditionalOnSingleRuntimeMode
import io.namastack.outbox.config.OutboxCoreInfrastructureAutoConfiguration
import io.namastack.outbox.instance.OutboxInstanceRegistry
import io.namastack.outbox.observability.OutboxObservabilityAutoConfiguration
import io.namastack.outbox.partition.PartitionCoordinator
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.time.Clock

/**
 * Configures background collection and gauge registration when the outbox infrastructure
 * beans are available and state metrics are enabled.
 *
 * Creates a dedicated scheduler and collector for this runtime. Collection starts through
 * Spring lifecycle management after instance registration and partition coordination.
 * Observation-based timers are configured separately.
 *
 * @author Roland Beisel
 * @since 1.7.0
 */
@AutoConfiguration(
    after = [
        OutboxCoreInfrastructureAutoConfiguration::class,
        OutboxObservabilityAutoConfiguration::class,
    ],
)
@ConditionalOnSingleRuntimeMode
@EnableConfigurationProperties(OutboxMetricsProperties::class)
@ConditionalOnClass(OutboxService::class)
@ConditionalOnProperty(
    name = ["namastack.outbox.enabled", "namastack.outbox.observability.metrics.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@ConditionalOnBean(value = [OutboxRecordRepository::class, PartitionCoordinator::class, OutboxInstanceRegistry::class])
internal class OutboxInstanceMetricsAutoConfiguration {
    @Bean(OutboxMetricsCollector.SCHEDULER_NAME, autowireCandidate = false)
    @ConditionalOnMissingBean(name = [OutboxMetricsCollector.SCHEDULER_NAME])
    fun outboxMetricsScheduler(): ThreadPoolTaskScheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = 1
            isDaemon = true
            setThreadNamePrefix("outbox-metrics-")
            // The collector cancels its task. Skip Spring's coordinated wait for drivers that ignore interruption.
            setAcceptTasksAfterContextClose(true)
        }

    @Bean
    @ConditionalOnMissingBean(value = [OutboxMetricsCollector::class, OutboxInstanceMetricsMeterBinder::class])
    fun outboxMetricsCollector(
        recordRepository: OutboxRecordRepository,
        recordStatusRepository: ObjectProvider<OutboxRecordStatusRepository>,
        partitionCoordinator: PartitionCoordinator,
        instanceRegistry: OutboxInstanceRegistry,
        properties: OutboxMetricsProperties,
        clock: ObjectProvider<Clock>,
        beanFactory: BeanFactory,
    ): OutboxMetricsCollector =
        OutboxMetricsCollector(
            recordRepository = recordRepository,
            recordStatusRepository = recordStatusRepository.getIfAvailable(),
            partitionCoordinator = partitionCoordinator,
            instanceRegistry = instanceRegistry,
            properties = properties,
            clock = clock.getIfAvailable { Clock.systemUTC() },
            taskScheduler = beanFactory.getBean(OutboxMetricsCollector.SCHEDULER_NAME, TaskScheduler::class.java),
        )

    @Bean
    @ConditionalOnMissingBean
    fun outboxInstanceMetricsMeterBinder(
        collector: OutboxMetricsCollector,
        channelNameProvider: OutboxChannelNameProvider,
    ): OutboxInstanceMetricsMeterBinder = OutboxInstanceMetricsMeterBinder(collector, channelNameProvider)
}
