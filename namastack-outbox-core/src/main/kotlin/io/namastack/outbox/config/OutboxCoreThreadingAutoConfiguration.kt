package io.namastack.outbox.config

import io.namastack.outbox.OutboxProperties
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnThreading
import org.springframework.boot.task.SimpleAsyncTaskExecutorBuilder
import org.springframework.boot.task.SimpleAsyncTaskSchedulerBuilder
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder
import org.springframework.boot.thread.Threading
import org.springframework.context.annotation.Bean
import org.springframework.core.task.SimpleAsyncTaskExecutor
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

@AutoConfiguration
@ConditionalOnProperty(name = ["namastack.outbox.enabled"], havingValue = "true", matchIfMissing = true)
class OutboxCoreThreadingAutoConfiguration {
    @Bean("outboxTaskExecutor", autowireCandidate = false)
    @ConditionalOnMissingBean(name = ["outboxTaskExecutor"])
    @ConditionalOnThreading(Threading.PLATFORM)
    fun outboxTaskExecutor(
        builder: ThreadPoolTaskExecutorBuilder,
        properties: OutboxProperties,
    ): ThreadPoolTaskExecutor =
        builder
            .corePoolSize(properties.processing.executorCorePoolSize)
            .maxPoolSize(properties.processing.executorMaxPoolSize)
            .threadNamePrefix("outbox-proc-")
            .build()

    @Bean("outboxTaskExecutor", autowireCandidate = false)
    @ConditionalOnMissingBean(name = ["outboxTaskExecutor"])
    @ConditionalOnThreading(Threading.VIRTUAL)
    fun outboxTaskExecutorVirtualThreads(
        builder: SimpleAsyncTaskExecutorBuilder,
        properties: OutboxProperties,
    ): SimpleAsyncTaskExecutor =
        builder
            .concurrencyLimit(properties.processing.executorConcurrencyLimit)
            .threadNamePrefix("outbox-proc-")
            .build()

    @Bean("outboxDefaultScheduler", autowireCandidate = false)
    @ConditionalOnMissingBean(name = ["outboxDefaultScheduler"])
    @ConditionalOnThreading(Threading.PLATFORM)
    fun outboxDefaultScheduler(builder: ThreadPoolTaskSchedulerBuilder): ThreadPoolTaskScheduler =
        builder
            .poolSize(5)
            .threadNamePrefix("outbox-scheduler-")
            .build()

    @Bean("outboxDefaultScheduler", autowireCandidate = false)
    @ConditionalOnMissingBean(name = ["outboxDefaultScheduler"])
    @ConditionalOnThreading(Threading.VIRTUAL)
    fun outboxDefaultSchedulerVirtualThreads(builder: SimpleAsyncTaskSchedulerBuilder): SimpleAsyncTaskScheduler =
        builder
            .concurrencyLimit(5)
            .threadNamePrefix("outbox-scheduler-")
            .build()

    @Bean("outboxRebalancingScheduler", autowireCandidate = false)
    @ConditionalOnMissingBean(name = ["outboxRebalancingScheduler"])
    @ConditionalOnThreading(Threading.PLATFORM)
    fun outboxRebalancingScheduler(builder: ThreadPoolTaskSchedulerBuilder): ThreadPoolTaskScheduler =
        builder
            .poolSize(1)
            .threadNamePrefix("outbox-rebalancing-")
            .build()

    @Bean("outboxRebalancingScheduler", autowireCandidate = false)
    @ConditionalOnMissingBean(name = ["outboxRebalancingScheduler"])
    @ConditionalOnThreading(Threading.VIRTUAL)
    fun outboxRebalancingSchedulerVirtualThreads(builder: SimpleAsyncTaskSchedulerBuilder): SimpleAsyncTaskScheduler =
        builder
            .concurrencyLimit(1)
            .threadNamePrefix("outbox-rebalancing-")
            .build()
}
