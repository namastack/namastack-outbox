package io.namastack.outbox.observability.metrics

import io.namastack.outbox.OutboxChannelNameProvider
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.OutboxRecordStatusRepository
import io.namastack.outbox.OutboxService
import io.namastack.outbox.config.OutboxCoreInfrastructureAutoConfiguration
import io.namastack.outbox.instance.OutboxInstanceRegistry
import io.namastack.outbox.observability.OutboxObservabilityAutoConfiguration
import io.namastack.outbox.partition.PartitionCoordinator
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

/**
 * Conditional configuration that registers instance and cluster gauge metrics
 * when the required infrastructure beans are available.
 *
 * These beans are only present when a persistence module (JPA/JDBC/MongoDB) is included
 * and the outbox runtime is fully started, so this configuration gracefully skips
 * metric registration in unit tests or minimal setups.
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
@ConditionalOnClass(OutboxService::class)
@ConditionalOnProperty(name = ["namastack.outbox.enabled"], havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(value = [OutboxRecordRepository::class, PartitionCoordinator::class, OutboxInstanceRegistry::class])
internal class OutboxInstanceMetricsAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun outboxInstanceMetricsMeterBinder(
        recordRepository: OutboxRecordRepository,
        recordStatusRepository: ObjectProvider<OutboxRecordStatusRepository>,
        partitionCoordinator: PartitionCoordinator,
        instanceRegistry: OutboxInstanceRegistry,
        channelNameProvider: OutboxChannelNameProvider,
    ): OutboxInstanceMetricsMeterBinder =
        OutboxInstanceMetricsMeterBinder(
            recordRepository = recordRepository,
            recordStatusRepository = recordStatusRepository.getIfAvailable(),
            partitionCoordinator = partitionCoordinator,
            instanceRegistry = instanceRegistry,
            channelNameProvider = channelNameProvider,
        )
}
