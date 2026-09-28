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
import io.namastack.outbox.handler.OutboxHandlerInfrastructureFactory
import io.namastack.outbox.handler.invoker.OutboxFallbackHandlerInvoker
import io.namastack.outbox.handler.invoker.OutboxHandlerInvoker
import io.namastack.outbox.handler.registry.OutboxFallbackHandlerRegistry
import io.namastack.outbox.handler.registry.OutboxHandlerRegistry
import io.namastack.outbox.instance.OutboxInstanceRegistry
import io.namastack.outbox.instance.OutboxInstanceRepository
import io.namastack.outbox.instrumentation.OutboxInstrumentation
import io.namastack.outbox.partition.PartitionAssignmentCache
import io.namastack.outbox.partition.PartitionAssignmentRepository
import io.namastack.outbox.retry.OutboxRetryPolicy
import io.namastack.outbox.retry.OutboxRetryPolicyFactory
import io.namastack.outbox.retry.OutboxRetryPolicyRegistry
import io.namastack.outbox.runtime.toRuntimeSettings
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurationPackage
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Role
import org.springframework.scheduling.TaskScheduler
import java.time.Clock

@AutoConfiguration
@AutoConfigurationPackage(basePackages = ["io.namastack.outbox"])
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
    @ConditionalOnProperty(name = ["namastack.outbox.mode"], havingValue = "channels")
    @ConditionalOnMissingBean
    fun outboxHandlerInfrastructureFactory(
        beanFactory: BeanFactory,
        instrumentations: ObjectProvider<OutboxInstrumentation>,
    ): OutboxHandlerInfrastructureFactory =
        OutboxHandlerInfrastructureFactory(
            beanFactory = beanFactory,
            instrumentationsSupplier = { instrumentations.orderedStream().toList() },
        )

    @Bean
    @ConditionalOnSingleRuntimeMode
    @ConditionalOnMissingBean
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
                channelNameProvider.getObject()
            },
        )

    @Bean
    @ConditionalOnSingleRuntimeMode
    @ConditionalOnMissingBean
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
                channelNameProvider.getObject()
            },
        )

    @Bean
    @ConditionalOnSingleRuntimeMode
    @ConditionalOnMissingBean
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
            properties.toRuntimeSettings().instance,
            clock,
            taskScheduler,
            { observationRegistry.getIfAvailable { ObservationRegistry.NOOP } },
        )
    }

    @Bean
    @ConditionalOnSingleRuntimeMode
    @ConditionalOnMissingBean
    fun partitionAssignmentCache(
        partitionAssignmentRepository: PartitionAssignmentRepository,
    ): PartitionAssignmentCache =
        PartitionAssignmentCache(
            partitionAssignmentRepository = partitionAssignmentRepository,
        )

    @Bean("outboxRetryPolicy")
    @ConditionalOnSingleRuntimeMode
    @ConditionalOnMissingBean(name = ["outboxRetryPolicy"])
    fun defaultOutboxRetryPolicy(builder: OutboxRetryPolicy.Builder): OutboxRetryPolicy = builder.build()

    @Bean("outboxRetryPolicyBuilder")
    @ConditionalOnSingleRuntimeMode
    @ConditionalOnMissingBean(name = ["outboxRetryPolicyBuilder"])
    fun defaultOutboxRetryPolicyBuilder(properties: OutboxProperties): OutboxRetryPolicy.Builder =
        OutboxRetryPolicyFactory.createDefault(retrySettings = properties.toRuntimeSettings().retry)

    @Bean
    @ConditionalOnSingleRuntimeMode
    @ConditionalOnMissingBean
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
                channelNameProvider.getObject()
            },
        )

    companion object {
        @Bean
        @ConditionalOnSingleRuntimeMode
        @ConditionalOnMissingBean
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        @JvmStatic
        internal fun outboxHandlerRegistry(): OutboxHandlerRegistry = OutboxHandlerRegistry()

        @Bean
        @ConditionalOnSingleRuntimeMode
        @ConditionalOnMissingBean
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        @JvmStatic
        internal fun outboxFallbackHandlerRegistry(
            handlerRegistry: OutboxHandlerRegistry,
        ): OutboxFallbackHandlerRegistry = OutboxFallbackHandlerRegistry(handlerRegistry)

        @Bean
        @ConditionalOnSingleRuntimeMode
        @ConditionalOnMissingBean
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        @JvmStatic
        internal fun outboxRetryPolicyRegistry(
            beanFactory: BeanFactory,
            handlerRegistry: OutboxHandlerRegistry,
        ): OutboxRetryPolicyRegistry = OutboxRetryPolicyRegistry(beanFactory, handlerRegistry)

        @Bean
        @ConditionalOnSingleRuntimeMode
        @ConditionalOnMissingBean
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        @JvmStatic
        internal fun outboxHandlerBeanPostProcessor(
            handlerRegistry: OutboxHandlerRegistry,
            retryPolicyRegistry: OutboxRetryPolicyRegistry,
        ): OutboxHandlerBeanPostProcessor = OutboxHandlerBeanPostProcessor(handlerRegistry, retryPolicyRegistry)
    }
}
