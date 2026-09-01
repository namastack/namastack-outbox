package io.namastack.outbox.handler

import io.namastack.outbox.OutboxChannelNameProvider
import io.namastack.outbox.handler.invoker.OutboxFallbackHandlerInvoker
import io.namastack.outbox.handler.invoker.OutboxHandlerInvoker
import io.namastack.outbox.handler.registry.OutboxFallbackHandlerRegistry
import io.namastack.outbox.handler.registry.OutboxHandlerRegistry
import io.namastack.outbox.instrumentation.OutboxInstrumentation
import io.namastack.outbox.retry.OutboxRetryPolicyRegistry
import org.springframework.beans.factory.BeanFactory
import java.lang.reflect.Method

/**
 * Owns isolated handler registration and invocation state for one programmatic outbox runtime.
 *
 * Each instance creates its own handler and retry registries together with the primary and fallback
 * invokers backed by those registries. Handler beans remain Spring-managed objects; the supplied
 * [beanFactory] resolves handler-specific and default retry policy beans.
 *
 * @param beanFactory Spring bean factory used to resolve retry policy beans
 * @param instrumentation Instrumentation applied around primary and fallback invocations
 * @param channelNameProvider Provider for the logical outbox channel name
 *
 * @author Roland Beisel
 * @since 1.10.0
 */
class OutboxHandlerInfrastructure(
    beanFactory: BeanFactory,
    internal val instrumentation: OutboxInstrumentation = OutboxInstrumentation.NOOP,
    internal val channelNameProvider: OutboxChannelNameProvider = OutboxChannelNameProvider.DEFAULT,
) {
    internal val handlerRegistry = OutboxHandlerRegistry()
    internal val fallbackHandlerRegistry = OutboxFallbackHandlerRegistry(handlerRegistry)
    internal val retryPolicyRegistry = OutboxRetryPolicyRegistry(beanFactory, handlerRegistry)
    internal val handlerInvoker =
        OutboxHandlerInvoker(
            handlerRegistry = handlerRegistry,
            instrumentation = instrumentation,
            channelNameProvider = channelNameProvider,
        )
    internal val fallbackHandlerInvoker =
        OutboxFallbackHandlerInvoker(
            retryPolicyRegistry = retryPolicyRegistry,
            handlerRegistry = handlerRegistry,
            instrumentation = instrumentation,
            channelNameProvider = channelNameProvider,
        )

    private val registrar = OutboxHandlerRegistrar(handlerRegistry, retryPolicyRegistry)

    /**
     * Registers selected primary handler methods declared by an initialized bean.
     *
     * All declarations are validated before the predicate is applied. Matching fallback
     * declarations and retry policies are retained for every selected primary method.
     *
     * @param bean Initialized bean to inspect
     * @param beanName Spring name of the inspected bean
     * @param primaryMethodPredicate Predicate selecting primary methods after validation
     * @throws IllegalStateException if declaration relationships are ambiguous or a routing ID
     * collides with an existing registration in this infrastructure
     */
    fun register(
        bean: Any,
        beanName: String,
        primaryMethodPredicate: (Method) -> Boolean = { true },
    ) {
        registrar.register(bean, beanName, primaryMethodPredicate)
    }
}
