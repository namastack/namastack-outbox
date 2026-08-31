package io.namastack.outbox.observability.aop

import io.namastack.outbox.OutboxChannelNameProvider
import io.namastack.outbox.instrumentation.OutboxInstrumentation
import io.namastack.outbox.instrumentation.OutboxScheduleInvocation
import org.aopalliance.intercept.MethodInterceptor
import org.aopalliance.intercept.MethodInvocation
import kotlin.LazyThreadSafetyMode.SYNCHRONIZED

/**
 * AOP around-advice that routes `OutboxService.schedule(...)` calls through
 * [OutboxInstrumentation].
 *
 * @param instrumentationSupplier Lazy supplier for the shared [OutboxInstrumentation].
 * @param channelNameProviderSupplier Lazy supplier for the channel name provider.
 *
 * @author Roland Beisel
 * @since 1.7.0
 */
internal class OutboxScheduleObservationAdvice(
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
     * Intercepts the `schedule(...)` method and delegates it to
     * [OutboxInstrumentation.schedule].
     *
     * @param invocation The intercepted method invocation.
     * @return The return value of the intercepted method.
     */
    override fun invoke(invocation: MethodInvocation): Any? {
        val args = invocation.arguments
        val payload = args.firstOrNull() ?: return invocation.proceed()

        val key = extractKey(args)
        return instrumentation.schedule(
            invocation =
                OutboxScheduleInvocation(
                    payload = payload,
                    recordKey = key,
                    channel = channelNameProvider.getChannelName(),
                ),
            action = {
                invocation.proceed()
            },
        )
    }

    /**
     * Extracts the record key from the method arguments.
     *
     * @param args The method arguments.
     * @return The record key if present, or "auto-generated" if not.
     */
    private fun extractKey(args: Array<Any?>): String =
        if (args.size >= 2 && args[1] is String) args[1] as String else "auto-generated"
}
