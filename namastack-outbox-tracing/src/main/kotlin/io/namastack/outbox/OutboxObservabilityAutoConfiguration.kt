package io.namastack.outbox

import io.micrometer.tracing.Tracer
import io.micrometer.tracing.propagation.Propagator
import io.namastack.outbox.annotation.EnableOutbox
import io.namastack.outbox.handler.registry.OutboxHandlerRegistry
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.EnableAspectJAutoProxy

/**
 * Auto-configuration for outbox observability support.
 *
 * Configures distributed tracing components that capture and restore trace context
 * across the async boundary between outbox record creation and processing. This
 * configuration automatically activates when Micrometer Tracing is present and
 * outbox is enabled.
 */
@AutoConfiguration(
    after = [MicrometerTracingAutoConfiguration::class, OutboxCoreAutoConfiguration::class],
)
@ConditionalOnBean(
    value = [Tracer::class, Propagator::class],
    annotation = [EnableOutbox::class],
)
@EnableAspectJAutoProxy
class OutboxObservabilityAutoConfiguration {
    /**
     * Creates OutboxSpanFactory for generating consumer spans during record processing.
     *
     * Factory uses Micrometer tracer and propagator to create spans with links
     * to producer spans, following queue-based messaging conventions.
     *
     * @param tracer Micrometer tracer for creating and managing spans
     * @param propagator Micrometer propagator for extracting trace context
     * @param handlerRegistry Registry for looking up handler metadata
     * @return OutboxSpanFactory configured for outbox record tracing
     */
    @Bean
    @ConditionalOnMissingBean
    fun outboxSpanFactory(
        tracer: Tracer,
        propagator: Propagator,
        handlerRegistry: OutboxHandlerRegistry,
    ): OutboxSpanFactory = OutboxSpanFactory(tracer, propagator, handlerRegistry)

    /**
     * Creates AOP aspect that intercepts outbox record processing.
     *
     * Aspect creates consumer spans with restored trace context for each
     * processed outbox record, bridging the async gap between producer and consumer.
     *
     * @param outboxSpanFactory Factory for creating consumer spans
     * @param tracer Micrometer tracer for managing span lifecycle
     * @return OutboxHandlerTracingAspect for intercepting processor chain
     */
    @Bean
    @ConditionalOnMissingBean
    fun outboxHandlerTracingAspect(
        outboxSpanFactory: OutboxSpanFactory,
        tracer: Tracer,
    ): OutboxHandlerTracingAspect = OutboxHandlerTracingAspect(outboxSpanFactory, tracer)

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
