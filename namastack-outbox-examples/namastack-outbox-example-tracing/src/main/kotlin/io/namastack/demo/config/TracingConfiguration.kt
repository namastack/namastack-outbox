package io.namastack.demo.config

import io.micrometer.tracing.Tracer
import io.micrometer.tracing.propagation.Propagator
import io.namastack.demo.OutboxTracingContextProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Configuration for observability support.
 *
 * Configures distributed tracing components that capture and restore trace context
 * across the async boundary between outbox record creation and processing.
 */
@Configuration
class TracingConfiguration {
    /**
     * Creates context provider for capturing trace context during record creation.
     *
     * Provider extracts current trace context (traceId, spanId) and stores it
     * in outbox record's context map for later restoration during processing.
     *
     * @param tracer Micrometer tracer for accessing current span
     * @param propagator Micrometer propagator for serializing trace context
     * @return OutboxTracingContextProvider for storing trace context in records
     */
    @Bean
    @ConditionalOnMissingBean
    fun outboxTracingContextProvider(
        tracer: Tracer,
        propagator: Propagator,
    ): OutboxTracingContextProvider = OutboxTracingContextProvider(tracer, propagator)
}
