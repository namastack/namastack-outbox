package io.namastack.outbox.kafka

import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.routing.OutboxRoute
import io.namastack.outbox.routing.OutboxRouting
import io.namastack.outbox.routing.OutboxRoutingConfigurer
import io.namastack.outbox.routing.selector.OutboxPayloadSelector
import java.util.function.Consumer

/**
 * Top-level function to create a Kafka routing configuration using DSL.
 *
 * ## Example (Kotlin)
 *
 * ```kotlin
 * val routing = kafkaOutboxRouting {
 *     route(OutboxPayloadSelector.type(OrderEvent::class.java)) {
 *         target("orders")
 *         key { payload, _ -> (payload as OrderEvent).orderId }
 *         headers { _, metadata -> metadata.context }
 *         mapping { payload, _ -> (payload as OrderEvent).toPublicEvent() }
 *         filter { payload, _ -> (payload as OrderEvent).status != "CANCELLED" }
 *     }
 *     defaults {
 *         target("domain-events")
 *     }
 * }
 * ```
 *
 * @param configurer Lambda to configure routing rules
 * @return A [KafkaOutboxRouting] instance
 */
fun kafkaOutboxRouting(configurer: KafkaOutboxRouting.Builder.() -> Unit): KafkaOutboxRouting {
    val builder = KafkaOutboxRouting.builder()
    builder.configurer()

    return builder.build()
}

/**
 * Kafka-specific routing configuration for outbox events.
 *
 * Extends [OutboxRouting] with Kafka-specific method naming (e.g., [resolveTopic]).
 *
 * ## Example (Kotlin)
 *
 * ```kotlin
 * @Bean
 * fun kafkaOutboxRouting() = kafkaOutboxRouting {
 *     route(OutboxPayloadSelector.type(OrderEvent::class.java)) {
 *         target("orders")
 *         key { payload, _ -> (payload as OrderEvent).orderId }
 *     }
 *     defaults {
 *         target("domain-events")
 *     }
 * }
 * ```
 *
 * ## Example (Java)
 *
 * ```java
 * @Bean
 * public KafkaOutboxRouting kafkaOutboxRouting() {
 *     return KafkaOutboxRouting.builder()
 *         .route(OutboxPayloadSelector.type(OrderEvent.class), route -> route
 *             .target("orders")
 *             .key((payload, metadata) -> ((OrderEvent) payload).getOrderId())
 *         )
 *         .defaults(route -> route.target("domain-events"))
 *         .build();
 * }
 * ```
 *
 * @author Roland Beisel
 * @since 1.1.0
 */
class KafkaOutboxRouting(
    rules: List<OutboxRoute>,
    defaultRule: OutboxRoute?,
) : OutboxRouting(rules, defaultRule) {
    /**
     * Resolves the Kafka topic for a given payload and metadata.
     *
     * @throws IllegalStateException if no matching route is found
     */
    fun resolveTopic(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): String = resolveTarget(payload, metadata)

    companion object {
        /**
         * Creates a new builder for routing configuration (Java-friendly).
         */
        @JvmStatic
        fun builder(): Builder = Builder()
    }

    /**
     * Java-friendly builder for [KafkaOutboxRouting].
     */
    class Builder {
        private val configurer = OutboxRoutingConfigurer()

        fun route(
            selector: OutboxPayloadSelector,
            routeConfigurer: OutboxRoute.Builder.() -> Unit,
        ): Builder {
            configurer.route(selector, routeConfigurer)

            return this
        }

        fun route(
            selector: OutboxPayloadSelector,
            routeConfigurer: Consumer<OutboxRoute.Builder>,
        ): Builder {
            configurer.route(selector, routeConfigurer)

            return this
        }

        fun defaults(routeConfigurer: OutboxRoute.Builder.() -> Unit): Builder {
            configurer.defaults(routeConfigurer)

            return this
        }

        fun defaults(routeConfigurer: Consumer<OutboxRoute.Builder>): Builder {
            configurer.defaults(routeConfigurer)

            return this
        }

        fun build(): KafkaOutboxRouting =
            KafkaOutboxRouting(
                rules = configurer.rules(),
                defaultRule = configurer.defaultRule(),
            )
    }
}
