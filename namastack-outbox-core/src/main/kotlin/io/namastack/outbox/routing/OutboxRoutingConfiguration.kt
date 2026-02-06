package io.namastack.outbox.routing

import io.namastack.outbox.handler.OutboxRecordMetadata
import java.util.function.Consumer

/**
 * Base configuration for externalizing outbox events.
 *
 * Provides routing logic and DSL infrastructure that can be extended
 * by module-specific configurations (Kafka, RabbitMQ, SNS, etc.).
 *
 * Rules are evaluated in declaration order - first match wins.
 *
 * @author Roland Beisel
 * @since 1.1.0
 */
abstract class OutboxRoutingConfiguration {
    private val rules = mutableListOf<OutboxRoutingRule>()
    private var defaultRule: OutboxRoutingRule? = null

    /**
     * Configures routing rules using the provided configurer.
     */
    protected fun configureRouting(configurer: OutboxRoutingConfigurer.() -> Unit) {
        OutboxRoutingConfigurer(rules) { defaultRule = it }.configurer()
    }

    /**
     * Configures routing rules using the provided configurer (Java-friendly).
     */
    protected fun configureRouting(configurer: Consumer<OutboxRoutingConfigurer>) {
        val routingConfigurer = OutboxRoutingConfigurer(rules) { defaultRule = it }
        configurer.accept(routingConfigurer)
    }

    /**
     * Finds the matching rule for the given payload and metadata.
     *
     * @return The first matching rule, or the default rule, or null if no match
     */
    fun findRule(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): OutboxRoutingRule? {
        for (rule in rules) {
            if (rule.selector.matches(payload, metadata)) {
                return rule
            }
        }
        return defaultRule
    }

    /**
     * Resolves the target destination for a given payload and metadata.
     *
     * @throws IllegalStateException if no matching route is found
     */
    fun resolveTarget(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): String =
        findRule(payload, metadata)?.target(payload, metadata)
            ?: throw IllegalStateException("No routing rule found for payload type: ${payload::class.java.name}")

    /**
     * Extracts the routing key for a given payload and metadata.
     */
    fun extractKey(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): String? = findRule(payload, metadata)?.key(payload, metadata)

    /**
     * Builds headers/attributes for a given payload and metadata.
     */
    fun buildHeaders(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): Map<String, String> = findRule(payload, metadata)?.headers(payload, metadata) ?: emptyMap()
}
