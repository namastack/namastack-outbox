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
        partitionCoordinator: PartitionCoordinator,
        instanceRegistry: OutboxInstanceRegistry,
    ): OutboxPartitionMetricsProvider {
        val repository =
            recordRepository.getIfAvailable()
                ?: throw IllegalStateException(
                    "OutboxRecordRepository bean is missing! The Outbox partition metrics cannot be registered because no persistence module (e.g. namastack-outbox-jpa) is included. Please add a persistence module to your dependencies to enable Outbox partition metrics.",
                )

        return OutboxPartitionMetricsProvider(repository, partitionCoordinator, instanceRegistry)
    }

    @Bean
    @ConditionalOnBean(OutboxPartitionMetricsProvider::class)
    fun outboxPartitionMetricsMeterBinder(
        partitionMetricsProvider: OutboxPartitionMetricsProvider,
    ): OutboxPartitionMetricsMeterBinder = OutboxPartitionMetricsMeterBinder(partitionMetricsProvider)
}
