package io.namastack.outbox.config

import io.namastack.outbox.Outbox
import io.namastack.outbox.OutboxProperties
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.OutboxService
import io.namastack.outbox.context.OutboxContextCollector
import io.namastack.outbox.context.OutboxContextProvider
import io.namastack.outbox.handler.OutboxHandlerBeanPostProcessor
import io.namastack.outbox.handler.invoker.OutboxFallbackHandlerInvoker
import io.namastack.outbox.handler.invoker.OutboxHandlerInvoker
import io.namastack.outbox.handler.registry.OutboxFallbackHandlerRegistry
import io.namastack.outbox.handler.registry.OutboxHandlerRegistry
import io.namastack.outbox.instance.OutboxInstanceRegistry
import io.namastack.outbox.instance.OutboxInstanceRepository
import io.namastack.outbox.partition.PartitionAssignmentCache
import io.namastack.outbox.partition.PartitionAssignmentRepository
import io.namastack.outbox.partition.PartitionCoordinator
import io.namastack.outbox.retry.OutboxRetryPolicy
import io.namastack.outbox.retry.OutboxRetryPolicyFactory
import io.namastack.outbox.retry.OutboxRetryPolicyRegistry
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurationPackage
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Role
import java.time.Clock

@AutoConfiguration
@AutoConfigurationPackage
@ConditionalOnProperty(name = ["namastack.outbox.enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(OutboxProperties::class)
class OutboxCoreInfrastructureAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun clock(): Clock = Clock.systemDefaultZone()

    @Bean
    @ConditionalOnMissingBean
    fun outboxContextCollector(providers: List<OutboxContextProvider>): OutboxContextCollector =
        OutboxContextCollector(providers = providers)

    @Bean
    @ConditionalOnMissingBean
    fun outboxHandlerInvoker(outboxHandlerRegistry: OutboxHandlerRegistry): OutboxHandlerInvoker =
        OutboxHandlerInvoker(outboxHandlerRegistry)

    @Bean
    @ConditionalOnMissingBean
    fun outboxFallbackHandlerInvoker(
        outboxFallbackHandlerRegistry: OutboxFallbackHandlerRegistry,
    ): OutboxFallbackHandlerInvoker = OutboxFallbackHandlerInvoker(outboxFallbackHandlerRegistry)

    @Bean
    @ConditionalOnMissingBean
    fun outboxInstanceRegistry(
        instanceRepository: OutboxInstanceRepository,
        properties: OutboxProperties,
        clock: Clock,
    ): OutboxInstanceRegistry = OutboxInstanceRegistry(instanceRepository, properties, clock)

    @Bean
    @ConditionalOnMissingBean
    fun partitionCoordinator(
        instanceRegistry: OutboxInstanceRegistry,
        partitionAssignmentRepository: PartitionAssignmentRepository,
        partitionAssignmentCache: PartitionAssignmentCache,
        clock: Clock,
    ): PartitionCoordinator =
        PartitionCoordinator(
            instanceRegistry = instanceRegistry,
            partitionAssignmentRepository = partitionAssignmentRepository,
            partitionAssignmentCache = partitionAssignmentCache,
            clock = clock,
        )

    @Bean
    @ConditionalOnMissingBean
    fun partitionAssignmentCache(
        partitionAssignmentRepository: PartitionAssignmentRepository,
    ): PartitionAssignmentCache =
        PartitionAssignmentCache(
            partitionAssignmentRepository = partitionAssignmentRepository,
        )

    @Bean("outboxRetryPolicy")
    @ConditionalOnMissingBean(name = ["outboxRetryPolicy"])
    fun defaultOutboxRetryPolicy(builder: OutboxRetryPolicy.Builder): OutboxRetryPolicy = builder.build()

    @Bean("outboxRetryPolicyBuilder")
    @ConditionalOnMissingBean(name = ["outboxRetryPolicyBuilder"])
    fun defaultOutboxRetryPolicyBuilder(properties: OutboxProperties): OutboxRetryPolicy.Builder =
        OutboxRetryPolicyFactory.createDefault(retryProperties = properties.retry)

    @Bean
    @ConditionalOnMissingBean
    fun outbox(
        outboxContextCollector: OutboxContextCollector,
        handlerRegistry: OutboxHandlerRegistry,
        recordRepository: OutboxRecordRepository,
        clock: Clock,
    ): Outbox =
        OutboxService(
            contextCollector = outboxContextCollector,
            handlerRegistry = handlerRegistry,
            outboxRecordRepository = recordRepository,
            clock = clock,
        )

    companion object {
        @Bean
        @ConditionalOnMissingBean
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        @JvmStatic
        internal fun outboxHandlerRegistry(): OutboxHandlerRegistry = OutboxHandlerRegistry()

        @Bean
        @ConditionalOnMissingBean
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        @JvmStatic
        internal fun outboxFallbackHandlerRegistry(): OutboxFallbackHandlerRegistry = OutboxFallbackHandlerRegistry()

        @Bean
        @ConditionalOnMissingBean
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        @JvmStatic
        internal fun outboxRetryPolicyRegistry(beanFactory: BeanFactory): OutboxRetryPolicyRegistry =
            OutboxRetryPolicyRegistry(beanFactory)

        @Bean
        @ConditionalOnMissingBean
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        @JvmStatic
        internal fun outboxHandlerBeanPostProcessor(
            handlerRegistry: OutboxHandlerRegistry,
            fallbackHandlerRegistry: OutboxFallbackHandlerRegistry,
            retryPolicyRegistry: OutboxRetryPolicyRegistry,
        ): OutboxHandlerBeanPostProcessor =
            OutboxHandlerBeanPostProcessor(handlerRegistry, fallbackHandlerRegistry, retryPolicyRegistry)
    }
}
