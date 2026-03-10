package io.namastack.outbox.tracing

import io.micrometer.observation.ObservationRegistry
import io.micrometer.tracing.Tracer
import io.micrometer.tracing.propagation.Propagator
import io.namastack.outbox.OutboxService
import io.namastack.outbox.config.OutboxCoreInfrastructureAutoConfiguration
import io.namastack.outbox.context.OutboxContextProvider
import io.namastack.outbox.handler.invoker.OutboxFallbackHandlerInvoker
import io.namastack.outbox.handler.invoker.OutboxHandlerInvoker
import io.namastack.outbox.observability.OutboxObservationDocumentation
import io.namastack.outbox.observability.OutboxProcessObservationContext.HandlerKind
import io.namastack.outbox.tracing.aop.OutboxInvokerMatcherPointcut
import io.namastack.outbox.tracing.aop.OutboxInvokerObservationAdvice
import io.namastack.outbox.tracing.context.OutboxTracingContextProvider
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

/**
 * Spring Boot auto-configuration that wires distributed tracing and Micrometer observations into
 * the outbox processing pipeline.
 *
 * The configuration is activated automatically when all of the following conditions are met:
 * - `namastack.outbox.enabled` is `true` (default).
 * - [OutboxService] is on the classpath.
 * - Both a [Tracer] and a [Propagator] bean are present (provided by any Spring Boot tracing
 *   bridge, e.g. `spring-boot-starter-opentelemetry` or `spring-boot-starter-brave`).
 *
 * It registers three beans:
 * 1. [OutboxTracingContextProvider] — captures the active span context at scheduling time and
 *    stores the serialized propagation headers (e.g. `traceparent`, `tracestate`) inside the
 *    outbox record's context map so they survive the async boundary.
 * 2. A primary-handler advisor — wraps [OutboxHandlerInvoker.dispatch] in an observation that
 *    restores the producer span as a child span and attaches outbox-specific tags.
 * 3. A fallback-handler advisor — identical to the primary advisor but targets
 *    [OutboxFallbackHandlerInvoker.dispatch] and sets the handler kind to `fallback`.
 *
 * @author Aleksander Zamojski
 * @since 1.2.0
 */
@AutoConfiguration(
    before = [OutboxCoreInfrastructureAutoConfiguration::class],
    after = [MicrometerTracingAutoConfiguration::class],
)
@ConditionalOnClass(OutboxService::class)
@ConditionalOnBean(value = [Tracer::class, Propagator::class])
@ConditionalOnProperty(name = ["namastack.outbox.enabled"], havingValue = "true", matchIfMissing = true)
internal class OutboxTracingAutoConfiguration {
    /**
     * Registers [OutboxTracingContextProvider] as the [OutboxContextProvider] responsible for
     * serializing the current trace context into outbox record context maps at scheduling time.
     *
     * @param tracer Micrometer [Tracer] used to access the currently active span.
     * @param propagator Micrometer [Propagator] used to inject trace headers into the context map.
     * @return A new [OutboxTracingContextProvider] instance.
     */
    @Bean
    @ConditionalOnMissingBean
    fun outboxTracingContextProvider(
        tracer: Tracer,
        propagator: Propagator,
    ): OutboxTracingContextProvider = OutboxTracingContextProvider(tracer, propagator)

    /**
     * Registers the Spring AOP advisor that wraps every [OutboxHandlerInvoker.dispatch] call in
     * an [OutboxObservationDocumentation.OUTBOX_RECORD_PROCESS] observation with
     * [HandlerKind.PRIMARY].
     *
     * The [ObservationRegistry] is retrieved lazily via [ObjectProvider] to avoid fetching not
     * initialized beans during application context startup.
     *
     * @param observationRegistry Lazy provider for the application's [ObservationRegistry].
     * @return An [Advisor] that applies [OutboxInvokerObservationAdvice] to the primary invoker.
     */
    @Bean
    @ConditionalOnMissingBean(name = ["outboxObservabilityHandlerAdvisor"])
    fun outboxHandlerObservabilityAdvisor(observationRegistry: ObjectProvider<ObservationRegistry>): Advisor {
        val pointcut = OutboxInvokerMatcherPointcut(OutboxHandlerInvoker::class.java)
        val advice = OutboxInvokerObservationAdvice(HandlerKind.PRIMARY, observationRegistry::getObject)
        return DefaultPointcutAdvisor(pointcut, advice)
    }

    /**
     * Registers the Spring AOP advisor that wraps every [OutboxFallbackHandlerInvoker.dispatch]
     * call in an [OutboxObservationDocumentation.OUTBOX_RECORD_PROCESS] observation with
     * [HandlerKind.FALLBACK].
     *
     * The [ObservationRegistry] is retrieved lazily via [ObjectProvider] to avoid fetching not
     * initialized beans during application context startup.
     *
     * @param observationRegistry Lazy provider for the application's [ObservationRegistry].
     * @return An [Advisor] that applies [OutboxInvokerObservationAdvice] to the fallback invoker.
     */
    @Bean
    @ConditionalOnMissingBean(name = ["outboxObservabilityFallbackAdvisor"])
    fun outboxFallbackObservabilityAdvisor(observationRegistry: ObjectProvider<ObservationRegistry>): Advisor {
        val pointcut = OutboxInvokerMatcherPointcut(OutboxFallbackHandlerInvoker::class.java)
        val advice = OutboxInvokerObservationAdvice(HandlerKind.FALLBACK, observationRegistry::getObject)
        return DefaultPointcutAdvisor(pointcut, advice)
    }
}
