package io.namastack.outbox

import io.micrometer.tracing.Tracer
import io.micrometer.tracing.propagation.Propagator
import io.namastack.outbox.annotation.EnableOutbox
import io.namastack.outbox.handler.OutboxHandlerRegistry
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration
import org.springframework.context.annotation.Bean

/**
 * Auto-configuration class for Outbox tracing functionality.
 */
@AutoConfiguration(
    before = [OutboxCoreAutoConfiguration::class],
    after = [MicrometerTracingAutoConfiguration::class],
)
@ConditionalOnBean(
    value = [Tracer::class, Propagator::class],
    annotation = [EnableOutbox::class],
)
internal class OutboxTracingAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    internal fun outboxTraceContextProvider(
        tracer: Tracer,
        propagator: Propagator,
    ): OutboxTraceContextProvider {
        println("OutboxTracingAutoConfiguration: Creating OutboxTraceContextProvider bean")
        return OutboxTraceContextProvider(
            tracer = tracer,
            propagator = propagator,
        )
    }

    @Bean
    @ConditionalOnMissingBean
    internal fun outboxTraceContextPropagator(
        tracer: Tracer,
        propagator: Propagator,
        handlerRegistry: OutboxHandlerRegistry,
    ): OutboxTraceContextPropagator {
        println("OutboxTracingAutoConfiguration: Creating OutboxTraceContextPropagator bean")
        return OutboxTraceContextPropagator(
            tracer = tracer,
            propagator = propagator,
            handlerRegistry = handlerRegistry,
        )
    }
}
