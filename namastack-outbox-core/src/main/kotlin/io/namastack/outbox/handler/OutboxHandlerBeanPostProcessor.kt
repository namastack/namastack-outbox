package io.namastack.outbox.handler

import io.namastack.outbox.handler.assembly.HandlerRegistrationAssembler
import io.namastack.outbox.handler.discovery.HandlerDiscovery
import io.namastack.outbox.handler.registry.OutboxHandlerRegistry
import io.namastack.outbox.retry.OutboxRetryPolicyRegistry
import org.springframework.beans.factory.config.BeanPostProcessor

/**
 * Spring BeanPostProcessor that discovers and registers handlers with their fallbacks.
 *
 * Called for each bean after Spring completes its initialization. Delegates declaration discovery and
 * complete registration assembly to focused handler components.
 *
 * Complete registrations are installed atomically in the primary registry. Fallback and retry
 * facades read their handler-specific data from those registrations without maintaining projections.
 *
 * @param handlerRegistry Handler registry for discovered handlers
 * @param retryPolicyRegistry Retry policy registry for handler-specific policies
 *
 * @author Roland Beisel
 * @since 0.4.0
 */
internal class OutboxHandlerBeanPostProcessor(
    private val handlerRegistry: OutboxHandlerRegistry,
    retryPolicyRegistry: OutboxRetryPolicyRegistry,
) : BeanPostProcessor {
    private val assembler = HandlerRegistrationAssembler(retryPolicyRegistry)

    /**
     * Processes a bean after Spring completes its initialization callbacks.
     *
     * Discovers declarations, assembles complete registrations, and installs canonical and alias
     * routes in the central handler registry.
     *
     * @param bean The initialized bean
     * @param beanName The bean name in Spring context
     * @return The original bean unchanged
     */
    override fun postProcessAfterInitialization(
        bean: Any,
        beanName: String,
    ): Any {
        val registrations = assembler.assemble(HandlerDiscovery.discover(bean, beanName))

        if (registrations.isNotEmpty()) handlerRegistry.registerBatch(registrations)

        return bean
    }
}
