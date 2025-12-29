package io.namastack.outbox

import io.micrometer.tracing.Tracer
import io.micrometer.tracing.propagation.Propagator
import io.opentelemetry.api.OpenTelemetry
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigureAfter
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.EnableAspectJAutoProxy

/**
 * Auto-configuration for outbox observability support.
 */
@AutoConfiguration
@AutoConfigureAfter(OutboxCoreAutoConfiguration::class)
@EnableAspectJAutoProxy
class OutboxObservabilityAutoConfiguration {
    /**
     * Creates OutboxSpanFactory for generating consumer spans during record processing.
     *
     * Factory uses OpenTelemetry tracer and propagator to create spans with links
     * to producer spans, following queue-based messaging conventions.
     *
     * @param openTelemetry OpenTelemetry instance with configured tracer and propagators
     * @return OutboxSpanFactory configured for outbox record tracing
     */
    @Bean
    @ConditionalOnMissingBean
    fun outboxSpanFactory(openTelemetry: OpenTelemetry): OutboxSpanFactory {
        val tracer = openTelemetry.getTracer("outbox-handler-tracing")
        val propagator = openTelemetry.propagators.textMapPropagator

        return OutboxSpanFactory(tracer, propagator)
    }

    /**
     * Creates AOP aspect that intercepts outbox record processing.
     *
     * Aspect creates consumer spans with restored trace context for each
     * processed outbox record, bridging the async gap between producer and consumer.
     *
     * @param outboxSpanFactory Factory for creating consumer spans
     * @return OutboxHandlerTracingAspect for intercepting processor chain
     */
    @Bean
    @ConditionalOnMissingBean
    fun outboxHandlerTracingAspect(outboxSpanFactory: OutboxSpanFactory): OutboxHandlerTracingAspect =
        OutboxHandlerTracingAspect(outboxSpanFactory)

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
