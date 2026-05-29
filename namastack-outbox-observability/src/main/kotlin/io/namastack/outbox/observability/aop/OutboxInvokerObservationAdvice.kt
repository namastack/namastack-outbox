package io.namastack.outbox.observability.aop

import io.micrometer.observation.ObservationRegistry
import io.namastack.outbox.OutboxChannelNameProvider
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.handler.invoker.OutboxFallbackHandlerInvoker
import io.namastack.outbox.handler.invoker.OutboxHandlerInvoker
import io.namastack.outbox.observability.OutboxObservationDocumentation
import io.namastack.outbox.observability.OutboxObservationDocumentation.DefaultOutboxProcessObservationConvention
import io.namastack.outbox.observability.OutboxProcessObservationContext
import io.namastack.outbox.observability.OutboxProcessObservationConvention
import org.aopalliance.intercept.MethodInterceptor
import org.aopalliance.intercept.MethodInvocation
import kotlin.LazyThreadSafetyMode.SYNCHRONIZED

/**
 * AOP around-advice that wraps a single [OutboxHandlerInvoker.dispatch] or
 * [OutboxFallbackHandlerInvoker.dispatch] call in a Micrometer
 * [io.micrometer.observation.Observation].
 *
 * The observation is started just before the handler is invoked and stopped (with any error
 * recorded) when the handler returns. Because [OutboxProcessObservationContext] extends
 * [io.micrometer.observation.transport.ReceiverContext], the Micrometer tracing bridge
 * automatically extracts propagation headers (e.g. `traceparent`, `tracestate`) from
 * [OutboxRecord.context] and creates a child span under the original producer trace.
 *
 * One instance of this advice is registered per handler kind — a separate advisor covers
 * [OutboxHandlerInvoker] (primary) and another covers [OutboxFallbackHandlerInvoker] (fallback).
 *
 * @param handlerKind Whether this advice instruments the primary or the fallback handler.
 * @param observationRegistrySupplier Lazy supplier for the [ObservationRegistry]; resolved once
 *   on first use to avoid early-initialization ordering issues.
 * @param customOutboxConventionSupplier Optional lazy supplier for a custom [OutboxProcessObservationConvention].
 *   If no bean is present, the default convention will be used.
 * @param channelNameProviderSupplier Lazy supplier for the channel name provider.
 *
 * @author Aleksander Zamojski, Roland Beisel
 * @since 1.7.0
 */
internal class OutboxInvokerObservationAdvice(
    private val handlerKind: OutboxProcessObservationContext.HandlerKind,
    private val observationRegistrySupplier: () -> ObservationRegistry,
    private val customOutboxConventionSupplier: () -> OutboxProcessObservationConvention?,
    private val channelNameProviderSupplier: () -> OutboxChannelNameProvider,
) : MethodInterceptor {
    private val observationRegistry: ObservationRegistry by lazy(SYNCHRONIZED) {
        observationRegistrySupplier()
    }
    private val customOutboxConvention: OutboxProcessObservationConvention? by lazy(SYNCHRONIZED) {
        customOutboxConventionSupplier()
    }
    private val channelNameProvider: OutboxChannelNameProvider by lazy(SYNCHRONIZED) {
        channelNameProviderSupplier()
    }

    /**
     * Intercepts the `dispatch(OutboxRecord<*>)` call, wraps it in an
     * [OutboxObservationDocumentation.OUTBOX_RECORD_PROCESS] observation, and proceeds with the
     * original invocation.
     *
     * If the first argument is not an [OutboxRecord] (e.g. the pointcut matched an unexpected
     * method), the invocation is passed through without an observation.
     *
     * @param invocation The intercepted method invocation.
     * @return The return value of the intercepted method (always `null` for `dispatch`).
     */
    override fun invoke(invocation: MethodInvocation): Any? {
        val args = invocation.arguments
        val record = args.firstOrNull() as? OutboxRecord<*> ?: return invocation.proceed()

        val observation =
            OutboxObservationDocumentation.OUTBOX_RECORD_PROCESS.observation(
                customOutboxConvention,
                DefaultOutboxProcessObservationConvention.INSTANCE,
                { OutboxProcessObservationContext(record, handlerKind, channelNameProvider.getChannelName()) },
                observationRegistry,
            )

        return observation.observe {
            invocation.proceed()
        }
    }
}
