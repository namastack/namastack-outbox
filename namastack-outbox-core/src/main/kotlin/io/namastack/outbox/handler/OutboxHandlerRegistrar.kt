package io.namastack.outbox.handler

import io.namastack.outbox.handler.assembly.HandlerRegistrationAssembler
import io.namastack.outbox.handler.discovery.HandlerDiscovery
import io.namastack.outbox.handler.registry.OutboxHandlerRegistry
import io.namastack.outbox.retry.OutboxRetryPolicyRegistry
import java.lang.reflect.Method

/**
 * Registers selected handlers through the shared discovery and assembly pipeline.
 *
 * @param handlerRegistry Registry receiving complete handler registrations
 * @param retryPolicyRegistry Registry used to resolve explicitly configured retry policies
 *
 * @author Roland Beisel
 * @since 1.10.0
 */
internal class OutboxHandlerRegistrar(
    private val handlerRegistry: OutboxHandlerRegistry,
    retryPolicyRegistry: OutboxRetryPolicyRegistry,
) {
    private val assembler = HandlerRegistrationAssembler(retryPolicyRegistry)

    /**
     * Discovers and validates all declarations on a bean and registers the selected handlers.
     *
     * @param bean Initialized handler bean
     * @param beanName Spring name of the handler bean
     * @param handlerSelector Selects primary handler methods after declaration validation
     * @throws IllegalStateException if declarations are ambiguous or routing identities collide
     */
    fun register(
        bean: Any,
        beanName: String,
        handlerSelector: (Method) -> Boolean = { true },
    ) {
        val registrations =
            assembler.assemble(
                declarations = HandlerDiscovery.discover(bean, beanName),
                handlerSelector = handlerSelector,
            )

        if (registrations.isNotEmpty()) handlerRegistry.registerBatch(registrations)
    }
}
