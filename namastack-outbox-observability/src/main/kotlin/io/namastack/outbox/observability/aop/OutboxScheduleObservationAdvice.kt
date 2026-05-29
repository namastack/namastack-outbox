package io.namastack.outbox.observability.aop

import io.micrometer.observation.ObservationRegistry
import io.namastack.outbox.OutboxChannelNameProvider
import io.namastack.outbox.observability.OutboxObservationDocumentation
import io.namastack.outbox.observability.OutboxObservationDocumentation.DefaultOutboxScheduleObservationConvention
import io.namastack.outbox.observability.OutboxScheduleObservationContext
import io.namastack.outbox.observability.OutboxScheduleObservationConvention
import org.aopalliance.intercept.MethodInterceptor
import org.aopalliance.intercept.MethodInvocation
import kotlin.LazyThreadSafetyMode.SYNCHRONIZED

/**
 * AOP around-advice that wraps `OutboxService.schedule(...)` calls in a Micrometer Observation.
 *
 * Produces the timer metric `outbox.record.schedule` with the outbox channel tag.
 *
 * @author Roland Beisel
 * @since 1.7.0
 */
internal class OutboxScheduleObservationAdvice(
    private val observationRegistrySupplier: () -> ObservationRegistry,
    private val customOutboxConventionSupplier: () -> OutboxScheduleObservationConvention?,
    private val channelNameProviderSupplier: () -> OutboxChannelNameProvider,
) : MethodInterceptor {
    private val observationRegistry: ObservationRegistry by lazy(SYNCHRONIZED) {
        observationRegistrySupplier()
    }
    private val customOutboxConvention: OutboxScheduleObservationConvention? by lazy(SYNCHRONIZED) {
        customOutboxConventionSupplier()
    }
    private val channelNameProvider: OutboxChannelNameProvider by lazy(SYNCHRONIZED) {
        channelNameProviderSupplier()
    }

    /**
     * Intercepts the `schedule(...)` method and wraps it in an observation.
     *
     * @param invocation The intercepted method invocation.
     * @return The return value of the intercepted method.
     */
    override fun invoke(invocation: MethodInvocation): Any? {
        val args = invocation.arguments
        val payload = args.firstOrNull() ?: return invocation.proceed()

        val key = extractKey(args)
        val context =
            OutboxScheduleObservationContext(
                payloadType = payload::class.simpleName ?: "Unknown",
                recordKey = key,
                channel = channelNameProvider.getChannelName(),
            )

        val observation =
            OutboxObservationDocumentation.OUTBOX_RECORD_SCHEDULE.observation(
                customOutboxConvention,
                DefaultOutboxScheduleObservationConvention.INSTANCE,
                { context },
                observationRegistry,
            )

        return observation.observe { invocation.proceed() }
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
