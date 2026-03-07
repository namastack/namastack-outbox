package io.namastack.outbox.aop

import io.micrometer.observation.ObservationRegistry
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.observability.OutboxObservationDocumentation
import io.namastack.outbox.observability.OutboxProcessObservationContext
import org.aopalliance.intercept.MethodInterceptor
import org.aopalliance.intercept.MethodInvocation

/**
 * Wraps [io.namastack.outbox.handler.invoker.OutboxHandlerInvoker.dispatch] in an observation
 * so async outbox processing continues the right tracing/observation context.
 */
internal class OutboxInvokerObservationAdvice(
    private val handlerType: OutboxProcessObservationContext.HandlerType,
    private val observationRegistrySupplier: () -> ObservationRegistry,
) : MethodInterceptor {
    private val observationRegistry: ObservationRegistry by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        observationRegistrySupplier()
    }

    override fun invoke(invocation: MethodInvocation): Any? {
        val args = invocation.arguments
        val record = args.firstOrNull() as? OutboxRecord<*> ?: return invocation.proceed()

        val observation =
            OutboxObservationDocumentation.OUTBOX_RECORD_PROCESS.observation(
                null,
                OutboxObservationDocumentation.DefaultOutboxProcessObservationConvention.INSTANCE,
                { OutboxProcessObservationContext(record, handlerType) },
                observationRegistry,
            )

        return observation.observe {
            invocation.proceed()
        }
    }
}
