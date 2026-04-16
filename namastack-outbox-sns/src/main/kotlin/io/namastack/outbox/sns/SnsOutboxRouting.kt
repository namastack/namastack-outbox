package io.namastack.outbox.sns

import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.routing.OutboxRoute
import io.namastack.outbox.routing.OutboxRouting
import io.namastack.outbox.routing.OutboxRoutingConfigurer
import io.namastack.outbox.routing.selector.OutboxPayloadSelector
import java.util.function.Consumer

/**
 * Top-level function to create an SNS routing configuration using the Kotlin DSL.
 *
 * ## Example (Kotlin)
 *
 * ```kotlin
 * val routing = snsOutboxRouting {
 *     route(OutboxPayloadSelector.type(OrderEvent::class.java)) {
 *         target("arn:aws:sns:us-east-1:123456789012:orders")
 *         key { payload, _ -> (payload as OrderEvent).orderId }
 *         headers { _, metadata -> metadata.context }
 *         mapping { payload, _ -> (payload as OrderEvent).toPublicEvent() }
 *         filter { payload, _ -> (payload as OrderEvent).status != "CANCELLED" }
 *     }
 *     defaults {
 *         target("arn:aws:sns:us-east-1:123456789012:domain-events")
 *     }
 * }
 * ```
 *
 * @param configurer lambda to configure routes and defaults
 * @return a [SnsOutboxRouting] instance
 */
fun snsOutboxRouting(configurer: SnsOutboxRouting.Builder.() -> Unit): SnsOutboxRouting {
    val builder = SnsOutboxRouting.builder()
    builder.configurer()

    return builder.build()
}

/**
 * SNS-specific routing configuration for outbox events.
 *
 * Extends [OutboxRouting] with SNS-specific method naming (e.g., [resolveTopicArn]).
 *
 * ## Example (Kotlin)
 *
 * ```kotlin
 * @Bean
 * fun snsOutboxRouting() = snsOutboxRouting {
 *     route(OutboxPayloadSelector.type(OrderEvent::class.java)) {
 *         target("arn:aws:sns:us-east-1:123456789012:orders")
 *         key { payload, _ -> (payload as OrderEvent).orderId }
 *     }
 *     defaults {
 *         target("arn:aws:sns:us-east-1:123456789012:domain-events")
 *     }
 * }
 * ```
 *
 * ## Example (Java)
 *
 * ```java
 * @Bean
 * public SnsOutboxRouting snsOutboxRouting() {
 *     return SnsOutboxRouting.builder()
 *         .route(OutboxPayloadSelector.type(OrderEvent.class), route -> route
 *             .target("arn:aws:sns:us-east-1:123456789012:orders")
 *             .key((payload, metadata) -> ((OrderEvent) payload).getOrderId())
 *         )
 *         .defaults(route -> route.target("arn:aws:sns:us-east-1:123456789012:domain-events"))
 *         .build();
 * }
 * ```
 *
 * @author Roland Beisel
 * @since 1.3.0
 */
class SnsOutboxRouting(
    rules: List<OutboxRoute>,
    defaultRule: OutboxRoute?,
) : OutboxRouting(rules, defaultRule) {
    /**
     * Resolves the SNS topic ARN for a given payload and metadata.
     *
     * @throws IllegalStateException if no matching route is found and no defaults are configured
     */
    fun resolveTopicArn(
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
     * Java-friendly builder for [SnsOutboxRouting].
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

        fun build(): SnsOutboxRouting =
            SnsOutboxRouting(
                rules = configurer.rules(),
                defaultRule = configurer.defaultRule(),
            )
    }
}
