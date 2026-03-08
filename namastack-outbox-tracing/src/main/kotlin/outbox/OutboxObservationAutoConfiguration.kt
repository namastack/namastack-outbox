package io.namastack.outbox

import io.micrometer.observation.ObservationRegistry
import io.micrometer.tracing.Tracer
import io.micrometer.tracing.propagation.Propagator
import io.namastack.outbox.aop.OutboxInvokerMatcherPointcut
import io.namastack.outbox.aop.OutboxInvokerObservationAdvice
import io.namastack.outbox.config.OutboxCoreInfrastructureAutoConfiguration
import io.namastack.outbox.handler.invoker.OutboxFallbackHandlerInvoker
import io.namastack.outbox.handler.invoker.OutboxHandlerInvoker
import io.namastack.outbox.observability.OutboxProcessObservationContext.HandlerType
import org.springframework.aop.Advisor
import org.springframework.aop.support.DefaultPointcutAdvisor
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration
import org.springframework.context.annotation.Bean

@AutoConfiguration(
    before = [OutboxCoreInfrastructureAutoConfiguration::class],
    after = [MicrometerTracingAutoConfiguration::class],
)
@ConditionalOnClass(OutboxService::class)
@ConditionalOnBean(value = [Tracer::class, Propagator::class])
@ConditionalOnProperty(name = ["namastack.outbox.enabled"], havingValue = "true", matchIfMissing = true)
internal class OutboxObservationAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun outboxTracingContextProvider(
        tracer: Tracer,
        propagator: Propagator,
    ): OutboxTracingContextProvider = OutboxTracingContextProvider(tracer, propagator)

    @Bean
    @ConditionalOnMissingBean(name = ["outboxObservabilityHandlerAdvisor"])
    fun outboxObservabilityHandlerAdvisor(observationRegistry: ObjectProvider<ObservationRegistry>): Advisor {
        val pointcut = OutboxInvokerMatcherPointcut(OutboxHandlerInvoker::class.java)
        val advice = OutboxInvokerObservationAdvice(HandlerType.HANDLER, observationRegistry::getObject)
        return DefaultPointcutAdvisor(pointcut, advice)
    }

    @Bean
    @ConditionalOnMissingBean(name = ["outboxObservabilityFallbackAdvisor"])
    fun outboxObservabilityFallbackAdvisor(observationRegistry: ObjectProvider<ObservationRegistry>): Advisor {
        val pointcut = OutboxInvokerMatcherPointcut(OutboxFallbackHandlerInvoker::class.java)
        val advice = OutboxInvokerObservationAdvice(HandlerType.FALLBACK, observationRegistry::getObject)
        return DefaultPointcutAdvisor(pointcut, advice)
    }
}
