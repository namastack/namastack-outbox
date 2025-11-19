package io.namastack.outbox.routing

/**
 * Configuration for event routing in the outbox pattern.
 *
 * Defines routing rules for different event types. Each event type can have:
 * - A specific routing rule (via .route())
 * - A wildcard routing rule as fallback (via .routeAll())
 *
 * @author Roland Beisel
 * @since 0.4.0
 */
class RoutingConfiguration private constructor(
    internal val routes: Map<String, RoutingRule>,
) {
    /**
     * Gets the routing rule for the given event type.
     *
     * Returns the specific route for the event type, or falls back to the wildcard route.
     * If neither exists, throws an IllegalArgumentException.
     *
     * @param eventType The event type to get the routing rule for
     * @return The matching RoutingRule
     * @throws IllegalArgumentException if no route found for event type and no wildcard route configured
     */
    fun getRoute(eventType: String): RoutingRule =
        routes[eventType]
            ?: routes[WILDCARD_ROUTE_KEY]
            ?: throw IllegalArgumentException(
                "No routing rule found for event type '$eventType'. " +
                    "Configure with .route(\"$eventType\") { ... } or use .routeAll() as fallback",
            )

    /**
     * Builder for constructing RoutingConfiguration instances with fluent API.
     *
     * @author Roland Beisel
     * @since 0.4.0
     */
    class Builder {
        private val routes = mutableMapOf<String, RoutingRule>()

        /**
         * Defines a routing rule for a specific event type.
         *
         * If called multiple times with the same event type, the last definition wins.
         *
         * @param eventType The event type to configure (e.g., "order.created")
         * @param builder Lambda to configure the routing rule
         * @return This builder for method chaining
         */
        fun route(
            eventType: String,
            builder: RoutingRule.Builder.() -> Unit,
        ): Builder {
            val rule = RoutingRule.Builder().apply(builder).build()
            routes[eventType] = rule

            return this
        }

        /**
         * Defines a wildcard routing rule that applies to all event types without a specific rule.
         *
         * This acts as a fallback/default route. If called multiple times, the last definition wins.
         *
         * @param builder Lambda to configure the routing rule
         * @return This builder for method chaining
         */
        fun routeAll(builder: RoutingRule.Builder.() -> Unit): Builder {
            val rule = RoutingRule.Builder().apply(builder).build()
            routes[WILDCARD_ROUTE_KEY] = rule

            return this
        }

        /**
         * Builds the RoutingConfiguration.
         *
         * @return A new immutable RoutingConfiguration instance
         */
        fun build(): RoutingConfiguration = RoutingConfiguration(routes)
    }

    /**
     * Companion object providing factory methods and constants.
     *
     * @author Roland Beisel
     * @since 0.4.0
     */
    companion object {
        /**
         * Key used to store the wildcard routing rule.
         *
         * The wildcard key "*" is used as a fallback when no specific route is configured
         * for an event type.
         */
        const val WILDCARD_ROUTE_KEY = "*"

        /**
         * Creates a new builder for configuring RoutingConfiguration.
         *
         * @return A new Builder instance for fluent configuration
         */
        @JvmStatic
        fun builder() = Builder()

        /**
         * Creates a default RoutingConfiguration with sensible defaults.
         *
         * The default configuration:
         * - Routes all events using their eventType as the target/topic
         * - Sets the aggregateId as the partition key to maintain ordering
         * - Uses the raw payload without transformation
         *
         * @return A new RoutingConfiguration with default routing rules
         */
        fun default(): RoutingConfiguration =
            builder()
                .routeAll {
                    target { record ->
                        RoutingTarget
                            .forTarget(record.eventType)
                            .withKey(record.aggregateId)
                    }
                    mapper { record -> record.payload }
                }.build()
    }
}
