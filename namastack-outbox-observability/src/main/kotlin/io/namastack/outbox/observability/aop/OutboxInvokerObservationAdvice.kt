package io.namastack.outbox.observability.aop

import io.namastack.outbox.OutboxChannelNameProvider
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.handler.invoker.OutboxFallbackHandlerInvoker
import io.namastack.outbox.handler.invoker.OutboxHandlerInvoker
import io.namastack.outbox.instrumentation.OutboxInstrumentation
import io.namastack.outbox.instrumentation.OutboxProcessHandlerKind
import io.namastack.outbox.instrumentation.OutboxProcessInvocation
import org.aopalliance.intercept.MethodInterceptor
import org.aopalliance.intercept.MethodInvocation
import kotlin.LazyThreadSafetyMode.SYNCHRONIZED

/**
 * AOP around-advice that routes a single [OutboxHandlerInvoker.dispatch] or
 * [OutboxFallbackHandlerInvoker.dispatch] call through [OutboxInstrumentation].
 *
 * The instrumentation surrounds the handler invocation and receives the record, handler kind,
 * and channel as an [OutboxProcessInvocation].
 *
 * One instance of this advice is registered per handler kind — a separate advisor covers
 * [OutboxHandlerInvoker] (primary) and another covers [OutboxFallbackHandlerInvoker] (fallback).
 *
 * @param handlerKind Whether this advice instruments the primary or the fallback handler.
 * @param instrumentationSupplier Lazy supplier for the shared [OutboxInstrumentation].
 * @param channelNameProviderSupplier Lazy supplier for the channel name provider.
 *
 * @author Aleksander Zamojski, Roland Beisel
 * @since 1.7.0
 */
internal class OutboxInvokerObservationAdvice(
    private val handlerKind: OutboxProcessHandlerKind,
    private val instrumentationSupplier: () -> OutboxInstrumentation,
    private val channelNameProviderSupplier: () -> OutboxChannelNameProvider,
) : MethodInterceptor {
    private val instrumentation: OutboxInstrumentation by lazy(SYNCHRONIZED) {
        instrumentationSupplier()
    }
    private val channelNameProvider: OutboxChannelNameProvider by lazy(SYNCHRONIZED) {
        channelNameProviderSupplier()
    }

    /**
     * Intercepts the `dispatch(OutboxRecord<*>)` call, delegates it to
     * [OutboxInstrumentation.process], and proceeds with the original invocation.
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

        return instrumentation.process(
            invocation =
                OutboxProcessInvocation(
                    record = record,
                    handlerKind = handlerKind,
                    channel = channelNameProvider.getChannelName(),
                ),
            action = {
                invocation.proceed()
            },
        )
    }
}
