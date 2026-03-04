package io.namastack.outbox

import io.micrometer.tracing.Tracer
import io.micrometer.tracing.propagation.Propagator
import io.namastack.outbox.config.OutboxCoreInfrastructureAutoConfiguration
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
internal class OutboxTracingAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun outboxTracingContextProvider(
        tracer: Tracer,
        propagator: Propagator,
    ): OutboxTracingContextProvider = OutboxTracingContextProvider(tracer, propagator)

    @Bean
    @ConditionalOnMissingBean
    fun outboxSpanFactory(
        tracer: Tracer,
        propagator: Propagator,
    ): OutboxSpanFactory = OutboxSpanFactory(tracer, propagator)

    @Bean
    @ConditionalOnMissingBean
    fun outboxTracingContextRestorer(
        spanFactory: OutboxSpanFactory,
        tracer: Tracer,
    ): OutboxTracingContextRestorer = OutboxTracingContextRestorer(spanFactory, tracer)
}
