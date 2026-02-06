package io.namastack.outbox.kafka

import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.routing.OutboxRoutingConfiguration
import java.util.function.Consumer

/**
 * Configuration for routing outbox events to Kafka.
 *
 * Provides a DSL for configuring routing rules with Kafka-specific terminology.
 * Rules are evaluated in declaration order - first match wins.
 *
 * ## Example (Kotlin)
 *
 * ```kotlin
 * KafkaRoutingConfiguration
 *     .create()
 *     .routing {
 *         route(OutboxPayloadSelector.type(OrderPlacedEvent::class.java)) {
 *             topic("order-placed")
 *             key { event, _ -> (event as OrderPlacedEvent).orderId }
 *             headers { _, metadata -> metadata.context }
 *         }
 *         route(OutboxPayloadSelector.type(OrderEvent::class.java)) {
 *             topic("orders")
 *         }
 *         defaults {
 *             topic("domain-events")
 *         }
 *     }
 * ```
 *
 * ## Example (Java)
 *
 * ```java
 * KafkaRoutingConfiguration.create()
 *     .routing(routing -> routing
 *         .route(OutboxPayloadSelector.type(OrderPlacedEvent.class), rule -> rule
 *             .topic("order-placed")
 *             .key((event, metadata) -> ((OrderPlacedEvent) event).getOrderId())
 *         )
 *         .defaults(rule -> rule
 *             .topic("domain-events")
 *         )
 *     );
 * ```
 *
 * @author Roland Beisel
 * @since 1.1.0
 */
class KafkaRoutingConfiguration private constructor() : OutboxRoutingConfiguration() {
    companion object {
        /**
         * Creates a new Kafka routing configuration.
         */
        @JvmStatic
        fun create(): KafkaRoutingConfiguration = KafkaRoutingConfiguration()
    }

    /**
     * Configures routing rules with Kafka-specific DSL.
     */
    fun routing(configurer: KafkaRoutingConfigurer.() -> Unit): KafkaRoutingConfiguration {
        configureRouting { KafkaRoutingConfigurer(this).configurer() }
        return this
    }

    /**
     * Configures routing rules with Kafka-specific DSL (Java-friendly).
     */
    fun routing(configurer: Consumer<KafkaRoutingConfigurer>): KafkaRoutingConfiguration {
        configureRouting { configurer.accept(KafkaRoutingConfigurer(this)) }
        return this
    }

    /**
     * Resolves the Kafka topic for a given payload and metadata.
     *
     * @throws IllegalStateException if no matching route is found
     */
    fun resolveTopic(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): String = resolveTarget(payload, metadata)
}
