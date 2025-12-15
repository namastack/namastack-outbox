package io.namastack.outbox

import io.micrometer.tracing.Tracer
import io.micrometer.tracing.propagation.Propagator
import io.namastack.outbox.annotation.EnableOutbox
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/**
 * Auto-configuration class for Outbox tracing functionality.
 */
@AutoConfiguration(
    before = [OutboxCoreAutoConfiguration::class],
)
@ConditionalOnBean(annotation = [EnableOutbox::class])
internal class OutboxTracingAutoConfiguration {
    @Bean
    internal fun testRun(): Any {
        println("OutboxTracingAutoConfiguration: Auto-configuration loaded")
        return Any()
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(Tracer::class, Propagator::class)
    internal fun outboxTraceContextPropagator(
        tracer: Tracer,
        propagator: Propagator,
    ): OutboxTraceContextPropagator {
        println("OutboxTracingAutoConfiguration: Creating OutboxTraceContextPropagator bean")
        return OutboxTraceContextPropagator(
            tracer = tracer,
            propagator = propagator,
        )
    }
}
