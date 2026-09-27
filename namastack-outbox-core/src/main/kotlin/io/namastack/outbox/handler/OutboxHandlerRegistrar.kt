package io.namastack.outbox.handler

import io.namastack.outbox.handler.assembly.HandlerRegistrationAssembler
import io.namastack.outbox.handler.discovery.HandlerDiscovery
import io.namastack.outbox.handler.registry.OutboxHandlerRegistry
import io.namastack.outbox.retry.OutboxRetryPolicyRegistry
import java.lang.reflect.Method

/** Registers selected handlers through the shared discovery and assembly pipeline. */
internal class OutboxHandlerRegistrar(
    private val handlerRegistry: OutboxHandlerRegistry,
    retryPolicyRegistry: OutboxRetryPolicyRegistry,
) {
    private val assembler = HandlerRegistrationAssembler(retryPolicyRegistry)

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
