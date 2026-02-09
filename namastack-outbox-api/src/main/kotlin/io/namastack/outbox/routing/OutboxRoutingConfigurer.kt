package io.namastack.outbox.routing

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
    private val rules = mutableListOf<OutboxRoute>()
    private var defaultRule: OutboxRoute? = null

    /**
     * Adds a routing rule for payloads matching the given selector.
     *
     * @param selector The selector to match payloads
     * @param configurer Lambda to configure the route
     * @return This configurer for chaining
     */
    fun route(
        selector: OutboxPayloadSelector,
        configurer: OutboxRoute.Builder.() -> Unit,
    ): OutboxRoutingConfigurer {
        val builder = OutboxRoute.Builder(selector)
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
        configurer: Consumer<OutboxRoute.Builder>,
    ): OutboxRoutingConfigurer {
        val builder = OutboxRoute.Builder(selector)
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
    fun defaults(configurer: OutboxRoute.Builder.() -> Unit): OutboxRoutingConfigurer {
        val builder = OutboxRoute.Builder(OutboxPayloadSelector.all())
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
    fun defaults(configurer: Consumer<OutboxRoute.Builder>): OutboxRoutingConfigurer {
        val builder = OutboxRoute.Builder(OutboxPayloadSelector.all())
        configurer.accept(builder)
        defaultRule = builder.build()

        return this
    }

    /**
     * Returns the configured rules.
     */
    fun rules(): List<OutboxRoute> = rules.toList()

    /**
     * Returns the default rule, if configured.
     */
    fun defaultRule(): OutboxRoute? = defaultRule
}
