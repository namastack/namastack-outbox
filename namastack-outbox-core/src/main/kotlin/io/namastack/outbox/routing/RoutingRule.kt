package io.namastack.outbox.routing

import io.namastack.outbox.OutboxRecord

/**
 * Represents a routing rule for an outbox record.
 *
 * A routing rule defines:
 * - How to map/transform the event payload
 * - Which headers to attach to the message
 * - Where to route the event (target and optional key)
 *
 * @author Roland Beisel
 * @since 0.4.0
 */
class RoutingRule private constructor(
    val mapper: (OutboxRecord) -> Any,
    val headers: (OutboxRecord) -> Map<String, String>,
    val target: (OutboxRecord) -> RoutingTarget,
) {
    /**
     * Builder for creating RoutingRule instances with fluent API.
     */
    class Builder {
        private var mapperFn: (OutboxRecord) -> Any = { it.payload }
        private var headersFn: (OutboxRecord) -> Map<String, String> = { emptyMap() }
        private var targetFn: ((OutboxRecord) -> RoutingTarget)? = null

        /**
         * Sets the mapper function to transform the outbox record payload.
         *
         * @param fn Function to transform OutboxRecord to Any
         * @return This builder for chaining
         */
        fun mapper(fn: (OutboxRecord) -> Any) =
            apply {
                this.mapperFn = fn
            }

        /**
         * Adds headers to the routing rule.
         *
         * Can be called multiple times. Headers are merged (later calls override earlier ones).
         *
         * @param fn Function to extract headers from OutboxRecord
         * @return This builder for chaining
         */
        fun headers(fn: (OutboxRecord) -> Map<String, String>) =
            apply {
                val previous = headersFn
                headersFn = { record ->
                    previous.invoke(record) + fn.invoke(record)
                }
            }

        /**
         * Adds a single header to the routing rule.
         *
         * Can be called multiple times. Later calls override earlier ones for same key.
         *
         * @param key The header key
         * @param value The header value
         * @return This builder for chaining
         */
        fun header(
            key: String,
            value: String,
        ) = apply {
            val previous = headersFn
            headersFn = { record ->
                previous.invoke(record) + (key to value)
            }
        }

        /**
         * Sets the routing target using a custom function.
         *
         * @param fn Function to determine RoutingTarget from OutboxRecord
         * @return This builder for chaining
         */
        fun target(fn: (OutboxRecord) -> RoutingTarget) =
            apply {
                targetFn = fn
            }

        /**
         * Sets the routing target with a fixed destination and optional key.
         *
         * @param targetValue The target destination (e.g., "kafka:orders")
         * @param key Optional partition key
         * @return This builder for chaining
         */
        fun target(
            targetValue: String,
            key: String? = null,
        ) = apply {
            targetFn = { RoutingTarget(targetValue, key) }
        }

        /**
         * Builds the RoutingRule.
         *
         * @return A new RoutingRule instance
         * @throws IllegalStateException if target is not configured
         */
        fun build(): RoutingRule {
            val targetFunction =
                targetFn
                    ?: error("RoutingRule configuration incomplete: target must be set via .target() method")

            return RoutingRule(
                mapper = mapperFn,
                headers = headersFn,
                target = targetFunction,
            )
        }
    }
}
