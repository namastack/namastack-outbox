package io.namastack.outbox.routing

import io.namastack.outbox.routing.selector.OutboxPayloadSelector
import java.util.function.Consumer

/**
 * DSL configurer for routing rules.
 *
 * @author Roland Beisel
 * @since 1.1.0
 */
class OutboxRoutingConfigurer internal constructor(
    private val rules: MutableList<OutboxRoutingRule>,
    private val defaultRuleSetter: (OutboxRoutingRule) -> Unit,
) {
    /**
     * Adds a routing rule for payloads matching the given selector.
     */
    fun route(
        selector: OutboxPayloadSelector,
        configurer: OutboxRouteBuilder.() -> Unit,
    ) {
        val builder = OutboxRouteBuilder(selector)
        builder.configurer()
        rules.add(builder.build())
    }

    /**
     * Adds a routing rule for payloads matching the given selector (Java-friendly).
     */
    fun route(
        selector: OutboxPayloadSelector,
        configurer: Consumer<OutboxRouteBuilder>,
    ) {
        val builder = OutboxRouteBuilder(selector)
        configurer.accept(builder)
        rules.add(builder.build())
    }

    /**
     * Configures the default routing rule for unmatched payloads.
     */
    fun defaults(configurer: OutboxRouteBuilder.() -> Unit) {
        val builder = OutboxRouteBuilder(OutboxPayloadSelector.predicate { _, _ -> true })
        builder.configurer()
        defaultRuleSetter(builder.build())
    }

    /**
     * Configures the default routing rule for unmatched payloads (Java-friendly).
     */
    fun defaults(configurer: Consumer<OutboxRouteBuilder>) {
        val builder = OutboxRouteBuilder(OutboxPayloadSelector.predicate { _, _ -> true })
        configurer.accept(builder)
        defaultRuleSetter(builder.build())
    }
}
