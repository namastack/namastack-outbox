package io.namastack.outbox

import io.namastack.outbox.partition.PartitionCoordinator
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Bean

@AutoConfiguration
@ConditionalOnBean(annotation = [EnableOutbox::class])
internal class OutboxMetricsAutoConfiguration {
    @Bean
    fun outboxMetricsMeterBinder(
        outboxRecordStatusRepository: ObjectProvider<OutboxRecordStatusRepository>,
    ): OutboxRecordMetricsMeterBinder {
        val statusRepository =
            outboxRecordStatusRepository.getIfAvailable()
                ?: throw IllegalStateException(
                    "OutboxRecordStatusRepository bean is missing! The Outbox metrics cannot be registered because no persistence module (e.g. namastack-outbox-jpa) is included. Please add a persistence module to your dependencies to enable Outbox metrics.",
                )

        return OutboxRecordMetricsMeterBinder(statusRepository)
    }

    @Bean
    @ConditionalOnBean(PartitionCoordinator::class)
    fun outboxPartitionMetricsProvider(
        recordRepository: ObjectProvider<OutboxRecordRepository>,
        partitionCoordinator: ObjectProvider<PartitionCoordinator>,
        instanceRegistry: ObjectProvider<OutboxInstanceRegistry>,
    ): OutboxPartitionMetricsProvider {
        val repository =
            recordRepository.getIfAvailable()
                ?: throw IllegalStateException(
                    "OutboxRecordRepository bean is missing! The Outbox partition metrics cannot be registered.",
                )

        val coordinator =
            partitionCoordinator.getIfAvailable()
                ?: throw IllegalStateException(
                    "PartitionCoordinator bean is missing! The Outbox partition metrics cannot be registered.",
                )

        val registry =
            instanceRegistry.getIfAvailable()
                ?: throw IllegalStateException(
                    "OutboxInstanceRegistry bean is missing! The Outbox partition metrics cannot be registered.",
                )

        return OutboxPartitionMetricsProvider(repository, coordinator, registry)
    }

    @Bean
    @ConditionalOnBean(OutboxPartitionMetricsProvider::class)
    fun outboxPartitionMetricsMeterBinder(
        partitionMetricsProvider: ObjectProvider<OutboxPartitionMetricsProvider>,
    ): OutboxPartitionMetricsMeterBinder {
        val provider =
            partitionMetricsProvider.getIfAvailable()
                ?: throw IllegalStateException(
                    "OutboxPartitionMetricsProvider bean is missing! The Outbox partition metrics meter binder cannot be registered.",
                )

        return OutboxPartitionMetricsMeterBinder(provider)
    }
}
