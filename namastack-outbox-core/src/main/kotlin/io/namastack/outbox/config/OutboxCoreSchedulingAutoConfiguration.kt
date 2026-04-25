package io.namastack.outbox.config

import io.micrometer.observation.ObservationRegistry
import io.namastack.outbox.OutboxProcessingScheduler
import io.namastack.outbox.OutboxProperties
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.partition.PartitionCoordinator
import io.namastack.outbox.partition.PartitionDrainTracker
import io.namastack.outbox.processor.OutboxRecordProcessor
import io.namastack.outbox.trigger.OutboxPollingTrigger
import io.namastack.outbox.trigger.OutboxPollingTriggerFactory
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor
import java.time.Clock

@AutoConfiguration
@ConditionalOnProperty(name = ["namastack.outbox.enabled"], havingValue = "true", matchIfMissing = true)
class OutboxCoreSchedulingAutoConfiguration {
    @Configuration
    @EnableScheduling
    @ConditionalOnMissingBean(ScheduledAnnotationBeanPostProcessor::class)
    class OutboxEnableSchedulingConfiguration

    @Bean
    @ConditionalOnMissingBean
    fun outboxPollingTrigger(
        properties: OutboxProperties,
        clock: Clock,
    ): OutboxPollingTrigger =
        OutboxPollingTriggerFactory.create(
            properties = properties,
            clock = clock,
        )

    @Bean
    @ConditionalOnMissingBean
    fun outboxProcessingScheduler(
        trigger: OutboxPollingTrigger,
        observationRegistry: ObjectProvider<ObservationRegistry>,
        recordRepository: OutboxRecordRepository,
        recordProcessorChain: OutboxRecordProcessor,
        partitionCoordinator: PartitionCoordinator,
        partitionDrainTracker: PartitionDrainTracker,
        properties: OutboxProperties,
        clock: Clock,
        beanFactory: BeanFactory,
    ): OutboxProcessingScheduler {
        val taskScheduler = beanFactory.getBean(OutboxProcessingScheduler.SCHEDULER_NAME) as TaskScheduler
        val taskExecutor = beanFactory.getBean("outboxTaskExecutor") as TaskExecutor

        return OutboxProcessingScheduler(
            trigger = trigger,
            taskScheduler = taskScheduler,
            observationRegistry = { observationRegistry.getIfAvailable { ObservationRegistry.NOOP } },
            recordRepository = recordRepository,
            recordProcessorChain = recordProcessorChain,
            partitionCoordinator = partitionCoordinator,
            partitionDrainTracker = partitionDrainTracker,
            taskExecutor = taskExecutor,
            properties = properties,
            clock = clock,
        )
    }
}
