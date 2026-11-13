package io.namastack.outbox.config

import io.namastack.outbox.OutboxProperties
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.completion.OutboxCompletionBatcher
import io.namastack.outbox.completion.OutboxCompletionBatchingPersistence
import io.namastack.outbox.completion.OutboxCompletionPersistence
import io.namastack.outbox.completion.OutboxCompletionWriter
import io.namastack.outbox.runtime.toRuntimeSettings
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnThreading
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.task.SimpleAsyncTaskExecutorBuilder
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder
import org.springframework.boot.thread.Threading
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.DependsOn
import org.springframework.core.task.SimpleAsyncTaskExecutor
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.time.Duration

/**
 * Configures shared completion batching with a persistence-specific writer.
 *
 * Enabling batching without a writer fails application startup.
 *
 * @author Roland Beisel
 * @since 2.0.0
 */
@AutoConfiguration(before = [OutboxCoreProcessingAutoConfiguration::class])
@ConditionalOnSingleRuntimeMode
@ConditionalOnProperty(name = ["namastack.outbox.enabled"], havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "namastack.outbox.completion-batching", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(OutboxProperties::class)
class OutboxCompletionAutoConfiguration {
    @Bean("outboxCompletionExecutor", autowireCandidate = false)
    @ConditionalOnMissingBean(name = ["outboxCompletionExecutor"])
    @ConditionalOnThreading(Threading.PLATFORM)
    internal fun outboxCompletionExecutor(builder: ThreadPoolTaskExecutorBuilder): ThreadPoolTaskExecutor =
        builder
            .corePoolSize(1)
            .maxPoolSize(1)
            // A restarted worker can wait while the previous executor task finishes cleanup.
            .queueCapacity(1)
            .threadNamePrefix("outbox-completion-writer-")
            .awaitTermination(true)
            .awaitTerminationPeriod(Duration.ZERO)
            .build()
            .apply { isDaemon = true }

    @Bean("outboxCompletionExecutor", autowireCandidate = false)
    @ConditionalOnMissingBean(name = ["outboxCompletionExecutor"])
    @ConditionalOnThreading(Threading.VIRTUAL)
    internal fun outboxCompletionExecutorVirtualThreads(
        builder: SimpleAsyncTaskExecutorBuilder,
    ): SimpleAsyncTaskExecutor =
        builder
            .concurrencyLimit(1)
            .threadNamePrefix("outbox-completion-writer-")
            // The batcher owns the bounded wait; closing the executor must not cancel pending writes.
            .taskTerminationTimeout(Duration.ZERO)
            .cancelRemainingTasksOnClose(false)
            .build()

    @Bean
    @ConditionalOnMissingBean
    @DependsOn("outboxCompletionExecutor")
    internal fun outboxCompletionBatcher(
        beanFactory: BeanFactory,
        writer: OutboxCompletionWriter,
        properties: OutboxProperties,
    ): OutboxCompletionBatcher =
        OutboxCompletionBatcher(
            executor = beanFactory.getBean<TaskExecutor>("outboxCompletionExecutor"),
            shutdownTimeout = properties.processing.shutdownTimeout,
            writer = writer,
            settings = properties.toRuntimeSettings().completionBatching,
        )

    @Bean
    @ConditionalOnMissingBean(OutboxCompletionPersistence::class)
    internal fun batchingOutboxCompletionPersistence(
        recordRepository: OutboxRecordRepository,
        batcher: OutboxCompletionBatcher,
    ): OutboxCompletionPersistence = OutboxCompletionBatchingPersistence(recordRepository, batcher)
}
