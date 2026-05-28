package io.namastack.outbox.observability.tracing

import io.micrometer.tracing.Tracer
import io.micrometer.tracing.propagation.Propagator
import io.namastack.outbox.OutboxService
import io.namastack.outbox.config.OutboxCoreInfrastructureAutoConfiguration
import io.namastack.outbox.context.OutboxContextProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration
import org.springframework.context.annotation.Bean

/**
 * Conditional configuration that registers [OutboxObservabilityTracingContextProvider]
 * when Micrometer Tracing is available on the classpath.
 *
 * @author Roland Beisel
 * @since 1.7.0
 */
@AutoConfiguration(
    before = [OutboxCoreInfrastructureAutoConfiguration::class],
    after = [MicrometerTracingAutoConfiguration::class],
)
@ConditionalOnClass(OutboxService::class)
@ConditionalOnBean(value = [Tracer::class, Propagator::class])
internal class OutboxObservabilityTracingAutoConfiguration {
    /**
     * Registers the tracing context provider that serializes the current span context
     * into outbox record context maps at scheduling time.
     *
     * Only activated when no other [OutboxContextProvider] of typew
     * [OutboxObservabilityTracingContextProvider] or the legacy
     * `OutboxTracingContextProvider` is already present.
     */
    @Bean
    @ConditionalOnMissingBean(name = ["outboxTracingContextProvider", "outboxObservabilityTracingContextProvider"])
    fun outboxObservabilityTracingContextProvider(
        tracer: Tracer,
        propagator: Propagator,
    ): OutboxObservabilityTracingContextProvider = OutboxObservabilityTracingContextProvider(tracer, propagator)
}
