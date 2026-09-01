package io.namastack.outbox.config

import io.micrometer.observation.ObservationRegistry
import io.namastack.outbox.Outbox
import io.namastack.outbox.OutboxChannelNameProvider
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
import io.namastack.outbox.instrumentation.OutboxInstrumentation
import io.namastack.outbox.partition.PartitionAssignmentCache
import io.namastack.outbox.partition.PartitionAssignmentRepository
import io.namastack.outbox.partition.PartitionCoordinator
import io.namastack.outbox.retry.OutboxRetryPolicy
import io.namastack.outbox.retry.OutboxRetryPolicyFactory
import io.namastack.outbox.retry.OutboxRetryPolicyRegistry
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Role
import org.springframework.scheduling.TaskScheduler
import java.time.Clock

@AutoConfiguration
@ConditionalOnProperty(name = ["namastack.outbox.enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(OutboxProperties::class)
class OutboxCoreInfrastructureAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun outboxChannelNameProvider(): OutboxChannelNameProvider = OutboxChannelNameProvider.DEFAULT

    @Bean
    @ConditionalOnMissingBean
    fun clock(): Clock = Clock.systemDefaultZone()

    @Bean
    @ConditionalOnMissingBean
    fun outboxContextCollector(providers: ObjectProvider<OutboxContextProvider>): OutboxContextCollector =
        OutboxContextCollector(
            providersSupplier = { providers.orderedStream().toList() },
        )

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = ["namastack.outbox.mode"], havingValue = "single", matchIfMissing = true)
    fun outboxHandlerInvoker(
        outboxHandlerRegistry: OutboxHandlerRegistry,
        instrumentations: ObjectProvider<OutboxInstrumentation>,
        channelNameProvider: ObjectProvider<OutboxChannelNameProvider>,
    ): OutboxHandlerInvoker =
        OutboxHandlerInvoker(
            handlerRegistry = outboxHandlerRegistry,
            instrumentationSupplier = {
                OutboxInstrumentation.compose(instrumentations.orderedStream().toList())
            },
            channelNameProviderSupplier = {
                channelNameProvider.getIfAvailable { OutboxChannelNameProvider.DEFAULT }
            },
        )

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = ["namastack.outbox.mode"], havingValue = "single", matchIfMissing = true)
    fun outboxFallbackHandlerInvoker(
        retryPolicyRegistry: OutboxRetryPolicyRegistry,
        outboxHandlerRegistry: OutboxHandlerRegistry,
        instrumentations: ObjectProvider<OutboxInstrumentation>,
        channelNameProvider: ObjectProvider<OutboxChannelNameProvider>,
    ): OutboxFallbackHandlerInvoker =
        OutboxFallbackHandlerInvoker(
            retryPolicyRegistry = retryPolicyRegistry,
            handlerRegistry = outboxHandlerRegistry,
            instrumentationSupplier = {
                OutboxInstrumentation.compose(instrumentations.orderedStream().toList())
            },
            channelNameProviderSupplier = {
                channelNameProvider.getIfAvailable { OutboxChannelNameProvider.DEFAULT }
            },
        )

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = ["namastack.outbox.mode"], havingValue = "single", matchIfMissing = true)
    fun outboxInstanceRegistry(
        instanceRepository: OutboxInstanceRepository,
        properties: OutboxProperties,
        clock: Clock,
        beanFactory: BeanFactory,
        observationRegistry: ObjectProvider<ObservationRegistry>,
    ): OutboxInstanceRegistry {
        val taskScheduler = beanFactory.getBean(OutboxInstanceRegistry.SCHEDULER_NAME) as TaskScheduler
        return OutboxInstanceRegistry(
            instanceRepository,
            properties,
            clock,
            taskScheduler,
            { observationRegistry.getIfAvailable { ObservationRegistry.NOOP } },
        )
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = ["namastack.outbox.mode"], havingValue = "single", matchIfMissing = true)
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
    @ConditionalOnProperty(name = ["namastack.outbox.mode"], havingValue = "single", matchIfMissing = true)
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
    @ConditionalOnProperty(name = ["namastack.outbox.mode"], havingValue = "single", matchIfMissing = true)
    fun outbox(
        outboxContextCollector: OutboxContextCollector,
        handlerRegistry: OutboxHandlerRegistry,
        recordRepository: OutboxRecordRepository,
        clock: Clock,
        instrumentations: ObjectProvider<OutboxInstrumentation>,
        channelNameProvider: ObjectProvider<OutboxChannelNameProvider>,
    ): Outbox =
        OutboxService(
            contextCollector = outboxContextCollector,
            handlerRegistry = handlerRegistry,
            outboxRecordRepository = recordRepository,
            clock = clock,
            instrumentationSupplier = {
                OutboxInstrumentation.compose(instrumentations.orderedStream().toList())
            },
            channelNameProviderSupplier = {
                channelNameProvider.getIfAvailable { OutboxChannelNameProvider.DEFAULT }
            },
        )

    companion object {
        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(name = ["namastack.outbox.mode"], havingValue = "single", matchIfMissing = true)
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        @JvmStatic
        internal fun outboxHandlerRegistry(): OutboxHandlerRegistry = OutboxHandlerRegistry()

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(name = ["namastack.outbox.mode"], havingValue = "single", matchIfMissing = true)
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        @JvmStatic
        internal fun outboxFallbackHandlerRegistry(
            handlerRegistry: OutboxHandlerRegistry,
        ): OutboxFallbackHandlerRegistry = OutboxFallbackHandlerRegistry(handlerRegistry)

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(name = ["namastack.outbox.mode"], havingValue = "single", matchIfMissing = true)
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        @JvmStatic
        internal fun outboxRetryPolicyRegistry(
            beanFactory: BeanFactory,
            handlerRegistry: OutboxHandlerRegistry,
        ): OutboxRetryPolicyRegistry = OutboxRetryPolicyRegistry(beanFactory, handlerRegistry)

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(name = ["namastack.outbox.mode"], havingValue = "single", matchIfMissing = true)
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        @JvmStatic
        internal fun outboxHandlerBeanPostProcessor(
            handlerRegistry: OutboxHandlerRegistry,
            retryPolicyRegistry: OutboxRetryPolicyRegistry,
        ): OutboxHandlerBeanPostProcessor = OutboxHandlerBeanPostProcessor(handlerRegistry, retryPolicyRegistry)
    }
}
