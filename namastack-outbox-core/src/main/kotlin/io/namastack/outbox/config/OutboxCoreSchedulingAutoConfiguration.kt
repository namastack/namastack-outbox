package io.namastack.outbox.config

import io.namastack.outbox.OutboxProcessingScheduler
import io.namastack.outbox.OutboxProperties
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.partition.PartitionCoordinator
import io.namastack.outbox.processor.OutboxRecordProcessor
import io.namastack.outbox.trigger.OutboxPollingTrigger
import io.namastack.outbox.trigger.OutboxPollingTriggerFactory
import org.springframework.beans.factory.BeanFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.TaskScheduler
import java.time.Clock

@AutoConfiguration
@ConditionalOnProperty(name = ["namastack.outbox.enabled"], havingValue = "true", matchIfMissing = true)
class OutboxCoreSchedulingAutoConfiguration {
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
        recordRepository: OutboxRecordRepository,
        recordProcessorChain: OutboxRecordProcessor,
        partitionCoordinator: PartitionCoordinator,
        properties: OutboxProperties,
        clock: Clock,
        beanFactory: BeanFactory,
    ): OutboxProcessingScheduler {
        val taskScheduler = beanFactory.getBean("outboxDefaultScheduler") as TaskScheduler
        val taskExecutor = beanFactory.getBean("outboxTaskExecutor") as TaskExecutor

        return OutboxProcessingScheduler(
            trigger = trigger,
            taskScheduler = taskScheduler,
            recordRepository = recordRepository,
            recordProcessorChain = recordProcessorChain,
            partitionCoordinator = partitionCoordinator,
            taskExecutor = taskExecutor,
            properties = properties,
            clock = clock,
        )
    }
}
