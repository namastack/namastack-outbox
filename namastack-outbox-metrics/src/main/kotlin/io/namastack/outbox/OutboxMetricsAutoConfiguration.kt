package io.namastack.outbox

import io.namastack.outbox.annotation.EnableOutbox
import io.namastack.outbox.instance.OutboxInstanceRegistry
import io.namastack.outbox.partition.PartitionCoordinator
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Bean

/**
 * Auto-configuration class for Outbox metrics functionality.
 *
 * Provides Micrometer meter binders for monitoring outbox records and partitions
 * when the EnableOutbox annotation is present and a persistence module is available.
 *
 * Registers two main meter binders:
 * - OutboxRecordMetricsMeterBinder: Tracks record counts by status (NEW, FAILED, COMPLETED)
 * - OutboxPartitionMetricsMeterBinder: Tracks partition assignments and pending records
 *
 * Metrics enable monitoring of:
 * - Record processing health (success/failure rates)
 * - Load distribution across partitions
 * - Processing bottlenecks and backlog
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@AutoConfiguration
@ConditionalOnBean(annotation = [EnableOutbox::class])
internal class OutboxMetricsAutoConfiguration {
    /**
     * Creates the outbox record metrics meter binder.
     *
     * @param outboxRecordStatusRepository Provider for the outbox record status repository
     * @return Configured meter binder for record metrics
     * @throws IllegalStateException if no persistence module (JPA) is available
     */
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

    /**
     * Creates the outbox partition metrics provider.
     *
     * Aggregates partition statistics from the coordinator and record repository.
     * Used by the partition metrics meter binder to register gauges.
     *
     * @param recordRepository Provider for the outbox record repository
     * @param partitionCoordinator Provider for the partition coordinator
     * @param instanceRegistry Provider for the instance registry
     * @return Configured partition metrics provider
     * @throws IllegalStateException if required beans are missing
     */
    @Bean
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

    /**
     * Creates the outbox partition metrics meter binder.
     *
     * Registers gauges for partition assignments and pending record counts.
     *
     * @param partitionMetricsProvider Provider for partition processing statistics
     * @return Configured meter binder for partition metrics
     */
    @Bean
    fun outboxPartitionMetricsMeterBinder(
        partitionMetricsProvider: OutboxPartitionMetricsProvider,
    ): OutboxPartitionMetricsMeterBinder = OutboxPartitionMetricsMeterBinder(partitionMetricsProvider)
}
