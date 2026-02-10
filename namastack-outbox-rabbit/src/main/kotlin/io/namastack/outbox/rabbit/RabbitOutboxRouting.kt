package io.namastack.outbox.rabbit

import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.routing.OutboxRoute
import io.namastack.outbox.routing.OutboxRouting
import io.namastack.outbox.routing.OutboxRoutingConfigurer
import io.namastack.outbox.routing.selector.OutboxPayloadSelector
import java.util.function.Consumer

/**
 * Top-level function to create a Rabbit routing configuration using the Kotlin DSL.
 *
 * ## Example (Kotlin)
 *
 * ```kotlin
 * val routing = rabbitOutboxRouting {
 *     route(OutboxPayloadSelector.type(OrderEvent::class.java)) {
 *         target("orders-exchange")
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
 * @param configurer lambda to configure routes and defaults
 * @return a [RabbitOutboxRouting] instance
 */
fun rabbitOutboxRouting(configurer: RabbitOutboxRouting.Builder.() -> Unit): RabbitOutboxRouting {
    val builder = RabbitOutboxRouting.builder()
    builder.configurer()

    return builder.build()
}

/**
 * Rabbit-specific routing configuration for outbox events.
 *
 * Extends [OutboxRouting] with Rabbit-specific method naming (e.g., [resolveExchange]).
 *
 * ## Example (Kotlin)
 *
 * ```kotlin
 * @Bean
 * fun rabbitOutboxRouting() = rabbitOutboxRouting {
 *     route(OutboxPayloadSelector.type(OrderEvent::class.java)) {
 *         target("orders-exchange")
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
 * public RabbitOutboxRouting rabbitOutboxRouting() {
 *     return RabbitOutboxRouting.builder()
 *         .route(OutboxPayloadSelector.type(OrderEvent.class), route -> route
 *             .target("orders-exchange")
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
class RabbitOutboxRouting(
    rules: List<OutboxRoute>,
    defaultRule: OutboxRoute?,
) : OutboxRouting(rules, defaultRule) {
    /**
     * Resolves the Rabbit exchange for a given payload and metadata.
     *
     * @throws IllegalStateException if no matching route is found and no defaults are configured
     */
    fun resolveExchange(
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
     * Java-friendly builder for [RabbitOutboxRouting].
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

        fun build(): RabbitOutboxRouting =
            RabbitOutboxRouting(
                rules = configurer.rules(),
                defaultRule = configurer.defaultRule(),
            )
    }
}
