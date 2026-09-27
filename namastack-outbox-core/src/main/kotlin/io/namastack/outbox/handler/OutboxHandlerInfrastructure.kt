package io.namastack.outbox.handler

import io.namastack.outbox.OutboxChannelNameProvider
import io.namastack.outbox.handler.invoker.OutboxHandlerInvoker
import io.namastack.outbox.handler.registry.OutboxHandlerRegistry
import io.namastack.outbox.instrumentation.OutboxInstrumentation
import io.namastack.outbox.retry.OutboxRetryPolicy
import io.namastack.outbox.retry.OutboxRetryPolicyRegistry
import org.springframework.beans.factory.BeanFactory
import java.lang.reflect.Method

/**
 * Isolated handler registration and invocation state for one outbox runtime.
 *
 * Handler objects remain Spring-managed. Registering a handler only adds its selected methods and
 * related fallback and retry configuration to this infrastructure.
 *
 * @author Roland Beisel
 * @since 1.10.0
 */
class OutboxHandlerInfrastructure internal constructor(
    beanFactory: BeanFactory,
    defaultRetryPolicy: OutboxRetryPolicy,
    internal val instrumentation: OutboxInstrumentation,
    channelName: String,
) {
    internal val channelNameProvider = OutboxChannelNameProvider { channelName }
    internal val handlerRegistry = OutboxHandlerRegistry()
    internal val retryPolicyRegistry =
        OutboxRetryPolicyRegistry(
            beanFactory = beanFactory,
            handlerRegistry = handlerRegistry,
            defaultRetryPolicyProvider = { defaultRetryPolicy },
        )
    internal val handlerInvoker =
        OutboxHandlerInvoker(
            handlerRegistry = handlerRegistry,
            instrumentationSupplier = { instrumentation },
            channelNameProviderSupplier = { channelNameProvider },
        )

    private val registrar = OutboxHandlerRegistrar(handlerRegistry, retryPolicyRegistry)

    /**
     * Registers handler methods declared by an initialized Spring bean.
     *
     * @param bean Initialized handler bean
     * @param beanName Spring name of the handler bean
     * @param handlerSelector Selects primary handler methods for this runtime
     */
    fun register(
        bean: Any,
        beanName: String,
        handlerSelector: (Method) -> Boolean = { true },
    ) {
        registrar.register(bean, beanName, handlerSelector)
    }
}
