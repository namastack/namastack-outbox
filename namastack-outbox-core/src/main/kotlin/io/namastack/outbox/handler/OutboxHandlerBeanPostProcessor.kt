package io.namastack.outbox.handler

import io.namastack.outbox.handler.assembly.HandlerRegistration
import io.namastack.outbox.handler.assembly.HandlerRegistrationAssembler
import io.namastack.outbox.handler.discovery.HandlerDiscovery
import io.namastack.outbox.handler.registry.OutboxFallbackHandlerRegistry
import io.namastack.outbox.handler.registry.OutboxHandlerRegistry
import io.namastack.outbox.retry.OutboxRetryPolicyRegistry
import org.springframework.beans.factory.config.BeanPostProcessor

/**
 * Spring BeanPostProcessor that discovers and registers handlers with their fallbacks.
 *
 * Called for each bean after Spring instantiates it. Delegates declaration discovery and
 * complete registration assembly to focused handler components.
 *
 * Complete registrations are installed atomically in the primary registry. Fallback and retry
 * registries receive compatibility projections for consumers of the legacy facades.
 *
 * @param handlerRegistry Handler registry for discovered handlers
 * @param fallbackHandlerRegistry Fallback registry for discovered fallbacks
 * @param retryPolicyRegistry Retry policy registry for handler-specific policies
 *
 * @author Roland Beisel
 * @since 0.4.0
 */
internal class OutboxHandlerBeanPostProcessor(
    private val handlerRegistry: OutboxHandlerRegistry,
    private val fallbackHandlerRegistry: OutboxFallbackHandlerRegistry,
    private val retryPolicyRegistry: OutboxRetryPolicyRegistry,
) : BeanPostProcessor {
    private val assembler = HandlerRegistrationAssembler(retryPolicyRegistry)

    /**
     * Processes a bean after Spring instantiation.
     *
     * Discovers declarations, assembles complete registrations, installs canonical and alias
     * routes, and updates compatibility projections.
     *
     * @param bean The newly instantiated bean
     * @param beanName The bean name in Spring context
     * @return The original bean unchanged
     */
    override fun postProcessAfterInitialization(
        bean: Any,
        beanName: String,
    ): Any {
        val registrations = assembler.assemble(HandlerDiscovery.discover(bean, beanName))

        if (registrations.isNotEmpty()) handlerRegistry.registerBatch(registrations)

        registrations.forEach { registration ->
            val handler = registration.primary

            // Compatibility projections for processors that consume the legacy facades.
            registration.fallback?.let { fallback ->
                fallbackHandlerRegistry.register(handler.id, fallback)
            }

            registration.explicitRetryPolicy?.let { policy -> retryPolicyRegistry.register(handler.id, policy) }

            handler.aliases.forEach { alias -> registerAliasProjection(alias, registration) }
        }

        return bean
    }

    /** Projects lookup-only alias IDs into the legacy fallback and retry registries. */
    private fun registerAliasProjection(
        aliasId: String,
        registration: HandlerRegistration,
    ) {
        registration.fallback?.let { fallback ->
            fallbackHandlerRegistry.registerAlias(aliasId, fallback)
        }

        registration.explicitRetryPolicy?.let { policy -> retryPolicyRegistry.registerAlias(aliasId, policy) }
    }
}
