package io.namastack.outbox.observability

import io.micrometer.observation.ObservationRegistry
import io.namastack.outbox.OutboxChannelNameProvider
import io.namastack.outbox.OutboxService
import io.namastack.outbox.config.OutboxCoreInfrastructureAutoConfiguration
import io.namastack.outbox.handler.invoker.OutboxFallbackHandlerInvoker
import io.namastack.outbox.handler.invoker.OutboxHandlerInvoker
import io.namastack.outbox.observability.OutboxProcessObservationContext.HandlerKind
import io.namastack.outbox.observability.aop.OutboxInvokerMatcherPointcut
import io.namastack.outbox.observability.aop.OutboxInvokerObservationAdvice
import io.namastack.outbox.observability.aop.OutboxScheduleMatcherPointcut
import io.namastack.outbox.observability.aop.OutboxScheduleObservationAdvice
import org.springframework.aop.Advisor
import org.springframework.aop.support.DefaultPointcutAdvisor
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Role

/**
 * Spring Boot auto-configuration that provides Micrometer Observation-based metrics and tracing
 * for the outbox processing pipeline.
 *
 * Activated when:
 * - [OutboxService] is on the classpath (core module present)
 * - An [ObservationRegistry] bean is available
 * - `namastack.outbox.enabled` is `true` (default)
 *
 * Provides observation-based instrumentation for:
 * - **Handler dispatch** (`outbox.record.process`) — timer per handler invocation
 * - **Record scheduling** (`outbox.record.schedule`) — timer per schedule call
 *
 * All observations include a `outbox.channel` low-cardinality tag which defaults to `"default"`
 * in OSS mode and reflects the actual channel id in Pro multi-channel mode.
 *
 * @author Roland Beisel
 * @since 1.7.0
 */
@AutoConfiguration(before = [OutboxCoreInfrastructureAutoConfiguration::class])
@ConditionalOnClass(OutboxService::class, ObservationRegistry::class)
@ConditionalOnProperty(name = ["namastack.outbox.enabled"], havingValue = "true", matchIfMissing = true)
class OutboxObservabilityAutoConfiguration {
    /**
     * Provides the default [OutboxChannelNameProvider] bean.
     *
     * Returns the default implementation, which always returns "default" as the channel name.
     * In Pro multi-channel mode, the channel-specific child context provides its own bean.
     *
     * @return the default [OutboxChannelNameProvider]
     */
    @Bean
    @ConditionalOnMissingBean
    fun outboxChannelNameProvider(): OutboxChannelNameProvider = OutboxChannelNameProvider.DEFAULT

    companion object {
        /**
         * Advisor wrapping [OutboxHandlerInvoker.dispatch] in an observation.
         *
         * Registers an advisor that instruments handler invocations with observation-based metrics and tracing.
         *
         * @param observationRegistry provider for the [ObservationRegistry]
         * @param customConvention provider for a custom [OutboxProcessObservationConvention], if present
         * @param channelNameProvider provider for the [OutboxChannelNameProvider]
         * @return the [Advisor] for handler invocations
         */
        @Bean
        @ConditionalOnMissingBean(name = ["outboxObservabilityHandlerAdvisor"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        @JvmStatic
        fun outboxObservabilityHandlerAdvisor(
            observationRegistry: ObjectProvider<ObservationRegistry>,
            customConvention: ObjectProvider<OutboxProcessObservationConvention>,
            channelNameProvider: ObjectProvider<OutboxChannelNameProvider>,
        ): Advisor {
            val pointcut = OutboxInvokerMatcherPointcut(OutboxHandlerInvoker::class.java)
            val advice =
                OutboxInvokerObservationAdvice(
                    handlerKind = HandlerKind.PRIMARY,
                    observationRegistrySupplier = observationRegistry::getObject,
                    customOutboxConventionSupplier = customConvention::getIfAvailable,
                    channelNameProviderSupplier = {
                        channelNameProvider.getIfAvailable { OutboxChannelNameProvider.DEFAULT }
                    },
                )
            return DefaultPointcutAdvisor(pointcut, advice)
        }

        /**
         * Advisor wrapping [OutboxFallbackHandlerInvoker.dispatch] in an observation.
         *
         * Registers an advisor that instruments fallback handler invocations with observation-based metrics and tracing.
         *
         * @param observationRegistry provider for the [ObservationRegistry]
         * @param customConvention provider for a custom [OutboxProcessObservationConvention], if present
         * @param channelNameProvider provider for the [OutboxChannelNameProvider]
         * @return the [Advisor] for fallback handler invocations
         */
        @Bean
        @ConditionalOnMissingBean(name = ["outboxObservabilityFallbackAdvisor"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        @JvmStatic
        fun outboxObservabilityFallbackAdvisor(
            observationRegistry: ObjectProvider<ObservationRegistry>,
            customConvention: ObjectProvider<OutboxProcessObservationConvention>,
            channelNameProvider: ObjectProvider<OutboxChannelNameProvider>,
        ): Advisor {
            val pointcut = OutboxInvokerMatcherPointcut(OutboxFallbackHandlerInvoker::class.java)
            val advice =
                OutboxInvokerObservationAdvice(
                    handlerKind = HandlerKind.FALLBACK,
                    observationRegistrySupplier = observationRegistry::getObject,
                    customOutboxConventionSupplier = customConvention::getIfAvailable,
                    channelNameProviderSupplier = {
                        channelNameProvider.getIfAvailable { OutboxChannelNameProvider.DEFAULT }
                    },
                )
            return DefaultPointcutAdvisor(pointcut, advice)
        }

        /**
         * Advisor wrapping [OutboxService.schedule] in an observation.
         *
         * Registers an advisor that instruments record scheduling with observation-based metrics and tracing.
         *
         * @param observationRegistry provider for the [ObservationRegistry]
         * @param customConvention provider for a custom [OutboxScheduleObservationConvention], if present
         * @param channelNameProvider provider for the [OutboxChannelNameProvider]
         * @return the [Advisor] for schedule invocations
         */
        @Bean
        @ConditionalOnMissingBean(name = ["outboxObservabilityScheduleAdvisor"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        @JvmStatic
        fun outboxObservabilityScheduleAdvisor(
            observationRegistry: ObjectProvider<ObservationRegistry>,
            customConvention: ObjectProvider<OutboxScheduleObservationConvention>,
            channelNameProvider: ObjectProvider<OutboxChannelNameProvider>,
        ): Advisor {
            val pointcut = OutboxScheduleMatcherPointcut()
            val advice =
                OutboxScheduleObservationAdvice(
                    observationRegistrySupplier = observationRegistry::getObject,
                    customOutboxConventionSupplier = customConvention::getIfAvailable,
                    channelNameProviderSupplier = {
                        channelNameProvider.getIfAvailable { OutboxChannelNameProvider.DEFAULT }
                    },
                )
            return DefaultPointcutAdvisor(pointcut, advice)
        }
    }
}
