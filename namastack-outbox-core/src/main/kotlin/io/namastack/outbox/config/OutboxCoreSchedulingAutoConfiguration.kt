package io.namastack.outbox.config

import io.micrometer.observation.ObservationRegistry
import io.namastack.outbox.OutboxProcessingScheduler
import io.namastack.outbox.OutboxProperties
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.dispatch.KeyDispatcher
import io.namastack.outbox.dispatch.OutboxKeyDispatcherFactory
import io.namastack.outbox.instance.OutboxInstanceRegistry
import io.namastack.outbox.partition.PartitionAssignmentCache
import io.namastack.outbox.partition.PartitionAssignmentRepository
import io.namastack.outbox.partition.PartitionCoordinator
import io.namastack.outbox.processor.OutboxRecordProcessorChainInvoker
import io.namastack.outbox.runtime.toRuntimeSettings
import io.namastack.outbox.trigger.OutboxPollingTrigger
import io.namastack.outbox.trigger.OutboxPollingTriggerFactory
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.getBean
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
@ConditionalOnSingleRuntimeMode
@ConditionalOnProperty(name = ["namastack.outbox.enabled"], havingValue = "true", matchIfMissing = true)
class OutboxCoreSchedulingAutoConfiguration {
    @Configuration
    @EnableScheduling
    @ConditionalOnMissingBean(ScheduledAnnotationBeanPostProcessor::class)
    class OutboxEnableSchedulingConfiguration

    @Bean
    @ConditionalOnMissingBean
    fun partitionCoordinator(
        instanceRegistry: OutboxInstanceRegistry,
        partitionAssignmentRepository: PartitionAssignmentRepository,
        partitionAssignmentCache: PartitionAssignmentCache,
        properties: OutboxProperties,
        clock: Clock,
        beanFactory: BeanFactory,
        observationRegistry: ObjectProvider<ObservationRegistry>,
    ): PartitionCoordinator {
        val taskScheduler = beanFactory.getBean(OutboxProcessingScheduler.SCHEDULER_NAME) as TaskScheduler
        return PartitionCoordinator(
            instanceRegistry = instanceRegistry,
            partitionAssignmentRepository = partitionAssignmentRepository,
            partitionAssignmentCache = partitionAssignmentCache,
            clock = clock,
            taskScheduler = taskScheduler,
            rebalanceInterval = properties.instance.rebalanceInterval,
            observationRegistry = { observationRegistry.getIfAvailable { ObservationRegistry.NOOP } },
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun outboxPollingTrigger(
        properties: OutboxProperties,
        clock: Clock,
    ): OutboxPollingTrigger =
        OutboxPollingTriggerFactory.create(
            settings = properties.toRuntimeSettings().polling,
            clock = clock,
        )

    @Bean
    @ConditionalOnMissingBean
    fun outboxKeyDispatcher(
        trigger: OutboxPollingTrigger,
        recordRepository: OutboxRecordRepository,
        recordProcessorChainInvoker: OutboxRecordProcessorChainInvoker,
        properties: OutboxProperties,
        clock: Clock,
        beanFactory: BeanFactory,
    ): KeyDispatcher =
        OutboxKeyDispatcherFactory.create(
            settings = properties.toRuntimeSettings(),
            trigger = trigger,
            recordRepository = recordRepository,
            recordProcessorChainInvoker = recordProcessorChainInvoker,
            taskExecutor = beanFactory.getBean<TaskExecutor>("outboxTaskExecutor"),
            clock = clock,
        )

    @Bean
    @ConditionalOnMissingBean
    fun outboxProcessingScheduler(
        trigger: OutboxPollingTrigger,
        observationRegistry: ObjectProvider<ObservationRegistry>,
        partitionCoordinator: PartitionCoordinator,
        keyDispatcher: KeyDispatcher,
        properties: OutboxProperties,
        beanFactory: BeanFactory,
    ): OutboxProcessingScheduler {
        val taskScheduler = beanFactory.getBean(OutboxProcessingScheduler.SCHEDULER_NAME) as TaskScheduler

        return OutboxProcessingScheduler(
            trigger = trigger,
            taskScheduler = taskScheduler,
            observationRegistry = { observationRegistry.getIfAvailable { ObservationRegistry.NOOP } },
            partitionCoordinator = partitionCoordinator,
            keyDispatcher = keyDispatcher,
            settings = properties.toRuntimeSettings(),
        )
    }
}
