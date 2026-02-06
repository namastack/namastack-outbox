package io.namastack.outbox.kafka

import io.namastack.outbox.routing.OutboxRoutingConfigurer
import io.namastack.outbox.routing.selector.OutboxPayloadSelector
import java.util.function.Consumer

/**
 * Kafka-specific routing configurer that wraps [OutboxRoutingConfigurer]
 * and provides Kafka terminology (`topic()` instead of `target()`).
 *
 * @author Roland Beisel
 * @since 1.1.0
 */
class KafkaRoutingConfigurer internal constructor(
    private val delegate: OutboxRoutingConfigurer,
) {
    /**
     * Adds a routing rule for payloads matching the given selector.
     */
    fun route(
        selector: OutboxPayloadSelector,
        configurer: KafkaRouteBuilder.() -> Unit,
    ) {
        delegate.route(selector) { KafkaRouteBuilder(this).configurer() }
    }

    /**
     * Adds a routing rule for payloads matching the given selector (Java-friendly).
     */
    fun route(
        selector: OutboxPayloadSelector,
        configurer: Consumer<KafkaRouteBuilder>,
    ) {
        delegate.route(selector) { configurer.accept(KafkaRouteBuilder(this)) }
    }

    /**
     * Configures the default routing rule for unmatched payloads.
     */
    fun defaults(configurer: KafkaRouteBuilder.() -> Unit) {
        delegate.defaults { KafkaRouteBuilder(this).configurer() }
    }

    /**
     * Configures the default routing rule for unmatched payloads (Java-friendly).
     */
    fun defaults(configurer: Consumer<KafkaRouteBuilder>) {
        delegate.defaults { configurer.accept(KafkaRouteBuilder(this)) }
    }
}
