package io.namastack.outbox.routing

import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.routing.selector.OutboxPayloadSelector
import java.util.function.Consumer

/**
 * Builder for configuring outbox routing rules.
 *
 * Provides a fluent API to define routing rules with selectors and default fallback.
 * Rules are evaluated in declaration order - first match wins.
 *
 * @author Roland Beisel
 * @since 1.1.0
 */
class OutboxRoutingConfigurer {
    private val rules = mutableListOf<OutboxRoutingRule>()
    private var defaultRule: OutboxRoutingRule? = null

    /**
     * Adds a routing rule for payloads matching the given selector.
     *
     * @param selector The selector to match payloads
     * @param configurer Lambda to configure the route
     * @return This configurer for chaining
     */
    fun route(
        selector: OutboxPayloadSelector,
        configurer: OutboxRouteBuilder.() -> Unit,
    ): OutboxRoutingConfigurer {
        val builder = OutboxRouteBuilder(selector)
        builder.configurer()
        rules.add(builder.build())
        return this
    }

    /**
     * Adds a routing rule for payloads matching the given selector (Java-friendly).
     *
     * @param selector The selector to match payloads
     * @param configurer Consumer to configure the route
     * @return This configurer for chaining
     */
    fun route(
        selector: OutboxPayloadSelector,
        configurer: Consumer<OutboxRouteBuilder>,
    ): OutboxRoutingConfigurer {
        val builder = OutboxRouteBuilder(selector)
        configurer.accept(builder)
        rules.add(builder.build())
        return this
    }

    /**
     * Configures the default routing rule for unmatched payloads.
     *
     * @param configurer Lambda to configure the default route
     * @return This configurer for chaining
     */
    fun defaults(configurer: OutboxRouteBuilder.() -> Unit): OutboxRoutingConfigurer {
        val builder = OutboxRouteBuilder(OutboxPayloadSelector.predicate { _, _ -> true })
        builder.configurer()
        defaultRule = builder.build()
        return this
    }

    /**
     * Configures the default routing rule for unmatched payloads (Java-friendly).
     *
     * @param configurer Consumer to configure the default route
     * @return This configurer for chaining
     */
    fun defaults(configurer: Consumer<OutboxRouteBuilder>): OutboxRoutingConfigurer {
        val builder = OutboxRouteBuilder(OutboxPayloadSelector.predicate { _, _ -> true })
        configurer.accept(builder)
        defaultRule = builder.build()
        return this
    }

    /**
     * Returns the configured rules.
     */
    fun rules(): List<OutboxRoutingRule> = rules.toList()

    /**
     * Returns the default rule, if configured.
     */
    fun defaultRule(): OutboxRoutingRule? = defaultRule

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
