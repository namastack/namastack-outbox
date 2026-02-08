package io.namastack.outbox.kafka

import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.routing.OutboxRouteBuilder
import io.namastack.outbox.routing.OutboxRoutingConfigurer
import io.namastack.outbox.routing.selector.OutboxPayloadSelector
import java.util.function.BiFunction
import java.util.function.Consumer

/**
 * Top-level function to create a Kafka routing configuration using DSL.
 *
 * ## Example (Kotlin)
 *
 * ```kotlin
 * val config = kafkaRouting {
 *     route(OutboxPayloadSelector.type(OrderEvent::class.java)) {
 *         topic("orders")
 *         key { event, _ -> (event as OrderEvent).orderId }
 *         headers { _, metadata -> metadata.context }
 *         mapping { event, _ -> (event as OrderEvent).toPublicEvent() }
 *         filter { event, _ -> (event as OrderEvent).status != "CANCELLED" }
 *     }
 *     defaults {
 *         topic("domain-events")
 *     }
 * }
 * ```
 *
 * @param configurer Lambda to configure routing rules
 * @return An immutable [KafkaOutboxRouting]
 */
fun kafkaRouting(configurer: OutboxRoutingConfigurer.() -> Unit): KafkaOutboxRouting {
    val builder = OutboxRoutingConfigurer()
    builder.configurer()

    return KafkaOutboxRouting(builder)
}

/**
 * Configuration for routing outbox events to Kafka.
 *
 * This is a thin wrapper around [OutboxRoutingConfigurer] that provides
 * Kafka-specific method names like [resolveTopic] instead of `resolveTarget`.
 *
 * ## Example (Kotlin)
 *
 * ```kotlin
 * @Bean
 * fun kafkaRoutingConfiguration() = kafkaRouting {
 *     // Route OrderEvent to "orders" topic
 *     route(OutboxPayloadSelector.type(OrderEvent::class.java)) {
 *         topic("orders")
 *         key { event, _ -> (event as OrderEvent).orderId }
 *         headers { _, metadata -> metadata.context }
 *         mapping { event, _ -> (event as OrderEvent).toPublicEvent() }
 *         filter { event, _ -> (event as OrderEvent).status != "CANCELLED" }
 *     }
 *
 *     // Route by annotation
 *     route(OutboxPayloadSelector.annotation(HighPriority::class.java)) {
 *         topic("high-priority-events")
 *     }
 *
 *     // Route by context value
 *     route(OutboxPayloadSelector.contextValue("tenant", "premium")) {
 *         topic("premium-events")
 *     }
 *
 *     // Default route for unmatched payloads
 *     defaults {
 *         topic("domain-events")
 *     }
 * }
 * ```
 *
 * ## Example (Java)
 *
 * ```java
 * @Bean
 * public KafkaOutboxRouting kafkaRoutingConfiguration() {
 *     return KafkaOutboxRouting.builder()
 *         // Route OrderEvent to "orders" topic
 *         .route(OutboxPayloadSelector.type(OrderEvent.class), rule -> rule
 *             .topic("orders")
 *             .key((event, metadata) -> ((OrderEvent) event).getOrderId())
 *             .headers((event, metadata) -> metadata.getContext())
 *             .mapping((event, metadata) -> ((OrderEvent) event).toPublicEvent())
 *             .filter((event, metadata) -> !((OrderEvent) event).getStatus().equals("CANCELLED"))
 *         )
 *         // Route by annotation
 *         .route(OutboxPayloadSelector.annotation(HighPriority.class), rule -> rule
 *             .topic("high-priority-events")
 *         )
 *         // Route by context value
 *         .route(OutboxPayloadSelector.contextValue("tenant", "premium"), rule -> rule
 *             .topic("premium-events")
 *         )
 *         // Default route for unmatched payloads
 *         .defaults(rule -> rule.topic("domain-events"))
 *         .build();
 * }
 * ```
 *
 * ## Available Route Configuration Options
 *
 * | Method | Description |
 * |--------|-------------|
 * | `topic(String)` | Static Kafka topic name |
 * | `topic((Any, OutboxRecordMetadata) -> String)` | Dynamic topic resolver |
 * | `key((Any, OutboxRecordMetadata) -> String?)` | Partition key extractor |
 * | `headers((Any, OutboxRecordMetadata) -> Map<String, String>)` | Kafka headers provider |
 * | `mapping((Any, OutboxRecordMetadata) -> Any)` | Payload transformer |
 * | `filter((Any, OutboxRecordMetadata) -> Boolean)` | Predicate to skip externalization |
 *
 * @author Roland Beisel
 * @since 1.1.0
 */
class KafkaOutboxRouting internal constructor(
    private val configurer: OutboxRoutingConfigurer,
) {
    companion object {
        /**
         * Creates a new builder for Kafka routing configuration (Java-friendly).
         */
        @JvmStatic
        fun builder(): Builder = Builder()
    }

    /**
     * Resolves the Kafka topic for a given payload and metadata.
     *
     * @throws IllegalStateException if no matching route is found
     */
    fun resolveTopic(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): String = configurer.resolveTarget(payload, metadata)

    /**
     * Extracts the routing key for a given payload and metadata.
     */
    fun extractKey(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): String? = configurer.extractKey(payload, metadata)

    /**
     * Builds Kafka headers for a given payload and metadata.
     */
    fun buildHeaders(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): Map<String, String> = configurer.buildHeaders(payload, metadata)

    /**
     * Maps the payload to a different representation before sending to Kafka.
     */
    fun mapPayload(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): Any = configurer.findRule(payload, metadata)?.mapping(payload, metadata) ?: payload

    /**
     * Checks if the payload should be externalized to Kafka.
     * Returns false if the payload is filtered out.
     */
    fun shouldExternalize(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): Boolean = configurer.findRule(payload, metadata)?.filter(payload, metadata) ?: true

    /**
     * Java-friendly builder for [KafkaOutboxRouting].
     */
    class Builder {
        private val configurer = OutboxRoutingConfigurer()

        fun route(
            selector: OutboxPayloadSelector,
            routeConfigurer: Consumer<OutboxRouteBuilder>,
        ): Builder {
            configurer.route(selector, routeConfigurer)

            return this
        }

        fun defaults(routeConfigurer: Consumer<OutboxRouteBuilder>): Builder {
            configurer.defaults(routeConfigurer)

            return this
        }

        fun build(): KafkaOutboxRouting = KafkaOutboxRouting(configurer)
    }
}

/**
 * Sets a static Kafka topic.
 */
fun OutboxRouteBuilder.topic(topic: String) = target(topic)

/**
 * Sets a dynamic Kafka topic resolver.
 */
fun OutboxRouteBuilder.topic(resolver: (Any, OutboxRecordMetadata) -> String) = target(resolver)

/**
 * Sets a dynamic Kafka topic resolver (Java-friendly).
 */
fun OutboxRouteBuilder.topic(resolver: BiFunction<Any, OutboxRecordMetadata, String>) = target(resolver)
