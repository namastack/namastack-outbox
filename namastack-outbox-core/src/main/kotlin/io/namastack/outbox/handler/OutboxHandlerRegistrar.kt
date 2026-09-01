package io.namastack.outbox.handler

import io.namastack.outbox.handler.assembly.HandlerRegistrationAssembler
import io.namastack.outbox.handler.discovery.HandlerDiscovery
import io.namastack.outbox.handler.registry.OutboxHandlerRegistry
import io.namastack.outbox.retry.OutboxRetryPolicyRegistry
import java.lang.reflect.Method

/**
 * Registers complete handler declarations through the shared discovery and assembly pipeline.
 *
 * All declarations on a bean are discovered and validated before the supplied predicate selects
 * primary methods. Fallback declarations remain available while the selected registrations are
 * assembled and the resulting batch is installed atomically.
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
     * Discovers, validates, selects, assembles, and registers declarations from one bean.
     *
     * @param bean Initialized bean to inspect
     * @param beanName Spring name of the inspected bean
     * @param primaryMethodPredicate Predicate selecting primary methods after validation
     * @throws IllegalStateException if declaration relationships are ambiguous or a routing ID
     * collides with an existing registration
     */
    fun register(
        bean: Any,
        beanName: String,
        primaryMethodPredicate: (Method) -> Boolean = { true },
    ) {
        val registrations =
            assembler.assemble(
                declarations = HandlerDiscovery.discover(bean, beanName),
                primaryMethodPredicate = primaryMethodPredicate,
            )

        if (registrations.isNotEmpty()) handlerRegistry.registerBatch(registrations)
    }
}
