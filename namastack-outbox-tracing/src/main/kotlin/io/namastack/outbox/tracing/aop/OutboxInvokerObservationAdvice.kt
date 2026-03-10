package io.namastack.outbox.tracing.aop

import io.micrometer.observation.ObservationRegistry
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.handler.invoker.OutboxFallbackHandlerInvoker
import io.namastack.outbox.handler.invoker.OutboxHandlerInvoker
import io.namastack.outbox.observability.OutboxObservationDocumentation
import io.namastack.outbox.observability.OutboxProcessObservationContext
import org.aopalliance.intercept.MethodInterceptor
import org.aopalliance.intercept.MethodInvocation

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
 *
 * @author Aleksander Zamojski
 * @since 1.2.0
 */
internal class OutboxInvokerObservationAdvice(
    private val handlerKind: OutboxProcessObservationContext.HandlerKind,
    private val observationRegistrySupplier: () -> ObservationRegistry,
) : MethodInterceptor {
    /**
     * [ObservationRegistry] resolved lazily on the first [invoke] call so that the bean is
     * fully initialized before it is accessed.
     */
    private val observationRegistry: ObservationRegistry by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        observationRegistrySupplier()
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
                null,
                OutboxObservationDocumentation.DefaultOutboxProcessObservationConvention.INSTANCE,
                { OutboxProcessObservationContext(record, handlerKind) },
                observationRegistry,
            )

        return observation.observe {
            invocation.proceed()
        }
    }
}
