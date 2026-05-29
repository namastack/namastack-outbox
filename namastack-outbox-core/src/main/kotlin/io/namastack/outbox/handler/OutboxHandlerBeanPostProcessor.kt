package io.namastack.outbox.handler

import io.namastack.outbox.OutboxProperties
import io.namastack.outbox.handler.registry.OutboxFallbackHandlerRegistry
import io.namastack.outbox.handler.registry.OutboxHandlerRegistry
import io.namastack.outbox.handler.scanner.HandlerScanResult
import io.namastack.outbox.handler.scanner.RetryPolicyScanner
import io.namastack.outbox.handler.scanner.handler.AnnotatedHandlerScanner
import io.namastack.outbox.handler.scanner.handler.InterfaceHandlerScanner
import io.namastack.outbox.retry.OutboxRetryPolicyRegistry
import org.springframework.beans.factory.config.BeanPostProcessor

/**
 * Spring BeanPostProcessor that discovers and registers handlers with their fallbacks.
 *
 * Called for each bean after Spring instantiates it. Uses scanners to discover handlers:
 * - AnnotatedHandlerScanner: Finds @OutboxHandler annotated methods
 * - InterfaceHandlerScanner: Finds OutboxHandler/OutboxTypedHandler interface implementations
 *
 * Each scanner discovers both the handler and its associated fallback (if present).
 *
 * Registration per handler:
 * 1. Register handler in handler registry
 * 2. Register fallback (if present) with handler.id as key
 * 3. Register retry policy for handler
 *
 * @param handlerRegistry Handler registry for discovered handlers
 * @param fallbackHandlerRegistry Fallback registry for discovered fallbacks
 * @param retryPolicyRegistry Retry policy registry for handler-specific policies
 * @param legacyAliasMode Controls automatic alias registration for fqcnId and legacyId
 *
 * @author Roland Beisel
 * @since 0.4.0
 */
internal class OutboxHandlerBeanPostProcessor(
    private val handlerRegistry: OutboxHandlerRegistry,
    private val fallbackHandlerRegistry: OutboxFallbackHandlerRegistry,
    private val retryPolicyRegistry: OutboxRetryPolicyRegistry,
    private val legacyAliasMode: OutboxProperties.LegacyAliasMode = OutboxProperties.LegacyAliasMode.AUTO,
) : BeanPostProcessor {
    /**
     * Scanners that discover handlers with their associated fallbacks.
     * Each scanner returns HandlerScanResult containing handler + optional fallback.
     */
    private val handlerScanners = listOf(AnnotatedHandlerScanner(), InterfaceHandlerScanner())

    /**
     * Scanners that extract retry policies from handlers.
     */
    private val retryPolicyScanners = listOf(RetryPolicyScanner(retryPolicyRegistry))

    /**
     * Processes a bean after Spring instantiation.
     *
     * Scans for handlers and their fallbacks, then registers them:
     * 1. Scan bean for handlers using all scanners
     * 2. Register handler in handler registry
     * 3. Register legacy alias if bean is an AOP proxy (backward compatibility)
     * 4. Register fallback (if present) with handler.id
     * 5. Register retry policy for handler
     *
     * @param bean The newly instantiated bean
     * @param beanName The bean name in Spring context
     * @return The original bean unchanged
     */
    override fun postProcessAfterInitialization(
        bean: Any,
        beanName: String,
    ): Any {
        // 1. Scan for all handlers and their fallbacks in this bean
        val scanResults = handlerScanners.flatMap { it.scan(bean) }

        // 2. For each result: register handler + fallback + retry policy + all aliases
        scanResults.forEach { result ->
            val handler = result.handler

            // a. Register handler under its primary id
            handlerRegistry.register(handler)

            // b. Register fallback if present (1:1 mapping)
            result.fallback?.let { fallback ->
                fallbackHandlerRegistry.register(handler.id, fallback)
            }

            // c. Register retry policy for this handler
            retryPolicyScanners
                .mapNotNull { it.scan(handler) }
                .forEach { policy -> retryPolicyRegistry.register(handler.id, policy) }

            // d. Register all alias IDs for backward compatibility
            registerAliases(result)
        }

        return bean
    }

    /**
     * Registers all alias IDs in all registries so rows written with old IDs continue to dispatch.
     *
     * Aliases registered:
     * - fqcnId — when a logical id is set and [legacyAliasMode] is AUTO, the FQCN form becomes an
     *   alias so in-flight rows written before the upgrade still resolve.
     * - legacyId — CGLIB proxy variant; registered when [legacyAliasMode] is AUTO.
     * - explicitAliases — user-declared aliases for rows written before a class/method rename;
     *   always registered regardless of [legacyAliasMode].
     */
    private fun registerAliases(result: HandlerScanResult) {
        val handler = result.handler
        val aliases =
            buildSet {
                if (legacyAliasMode == OutboxProperties.LegacyAliasMode.AUTO) {
                    if (handler.id != handler.fqcnId) add(handler.fqcnId)
                    if (handler.id != handler.legacyId) add(handler.legacyId)
                }
                addAll(handler.explicitAliases)
                remove(handler.id)
            }

        if (aliases.isEmpty()) return

        val policies = retryPolicyScanners.mapNotNull { it.scan(handler) }

        for (aliasId in aliases) {
            handlerRegistry.registerAlias(aliasId, handler)
            result.fallback?.let { fallback ->
                fallbackHandlerRegistry.registerAlias(aliasId, fallback)
            }
            policies.forEach { policy -> retryPolicyRegistry.registerAlias(aliasId, policy) }
        }
    }
}
