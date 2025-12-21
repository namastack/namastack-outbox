package io.namastack.outbox.handler

import io.namastack.outbox.handler.registry.OutboxFallbackHandlerRegistry
import io.namastack.outbox.handler.registry.OutboxHandlerRegistry
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
 *
 * @author Roland Beisel
 * @since 0.4.0
 */
internal class OutboxHandlerBeanPostProcessor(
    private val handlerRegistry: OutboxHandlerRegistry,
    private val fallbackHandlerRegistry: OutboxFallbackHandlerRegistry,
    private val retryPolicyRegistry: OutboxRetryPolicyRegistry,
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
     * 3. Register fallback (if present) with handler.id
     * 4. Register retry policy for handler
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

        // 2. For each result: register handler + fallback + retry policy
        scanResults.forEach { result ->
            val handler = result.handler
            val handlerId = handler.id

            // a. Register handler
            handlerRegistry.register(handler)

            // b. Register fallback if present (1:1 mapping)
            result.fallback?.let { fallbackHandlerMethod ->
                fallbackHandlerRegistry.register(handlerId, fallbackHandlerMethod)
            }

            // c. Register retry policy for this handler
            retryPolicyScanners
                .mapNotNull { it.scan(handler) }
                .forEach { policy ->
                    retryPolicyRegistry.register(handlerId, policy)
                }
        }

        return bean
    }
}
