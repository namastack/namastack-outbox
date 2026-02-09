package io.namastack.outbox.routing

import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.routing.selector.OutboxPayloadSelector
import java.util.function.BiFunction
import java.util.function.BiPredicate
import java.util.function.Consumer

/**
 * A route that defines how outbox payloads are externalized.
 *
 * Combines a selector for matching payloads with configuration for:
 * - [target] - destination (Kafka topic, RabbitMQ exchange, SNS topic, etc.)
 * - [key] - routing/partition key
 * - [headers] - message headers/attributes
 * - [mapping] - payload transformation
 * - [filter] - predicate to skip externalization
 *
 * Routes are evaluated in declaration order - first match wins.
 *
 * Use [OutboxRoute.builder] to create instances.
 *
 * ## Example (Kotlin)
 *
 * ```kotlin
 * OutboxRoute.builder(OutboxPayloadSelector.type(OrderEvent::class.java)) {
 *     target("orders")
 *     key { payload, _ -> (payload as OrderEvent).orderId }
 *     headers { _, metadata -> metadata.context }
 *     mapping { payload, _ -> (payload as OrderEvent).toPublicEvent() }
 *     filter { payload, _ -> (payload as OrderEvent).status != "CANCELLED" }
 * }
 * ```
 *
 * ## Example (Java)
 *
 * ```java
 * OutboxRoute.builder(OutboxPayloadSelector.type(OrderEvent.class), route -> {
 *     route.target("orders");
 *     route.key((payload, metadata) -> ((OrderEvent) payload).getOrderId());
 *     route.headers((payload, metadata) -> metadata.getContext());
 *     route.mapping((payload, metadata) -> ((OrderEvent) payload).toPublicEvent());
 *     route.filter((payload, metadata) -> !((OrderEvent) payload).getStatus().equals("CANCELLED"));
 * });
 * ```
 *
 * @author Roland Beisel
 * @since 1.1.0
 */
class OutboxRoute internal constructor(
    private val selector: OutboxPayloadSelector,
    private val targetResolver: BiFunction<Any, OutboxRecordMetadata, String>,
    private val keyExtractor: BiFunction<Any, OutboxRecordMetadata, String?>,
    private val headersProvider: BiFunction<Any, OutboxRecordMetadata, Map<String, String>>,
    private val payloadMapper: BiFunction<Any, OutboxRecordMetadata, Any>,
    private val filterPredicate: BiPredicate<Any, OutboxRecordMetadata>,
) {
    companion object {
        /**
         * Creates a route with the given selector using Kotlin DSL syntax.
         *
         * @param selector The selector to match payloads
         * @param configurer Lambda to configure the route
         * @return The configured route
         */
        fun builder(
            selector: OutboxPayloadSelector,
            configurer: Builder.() -> Unit,
        ): OutboxRoute {
            val builder = Builder(selector)
            builder.configurer()

            return builder.build()
        }

        /**
         * Creates a route with the given selector using Java-friendly syntax.
         *
         * @param selector The selector to match payloads
         * @param configurer Consumer to configure the route
         * @return The configured route
         */
        @JvmStatic
        fun builder(
            selector: OutboxPayloadSelector,
            configurer: Consumer<Builder>,
        ): OutboxRoute {
            val builder = Builder(selector)
            configurer.accept(builder)

            return builder.build()
        }
    }

    /**
     * Resolves the target destination for the given payload and metadata.
     */
    fun target(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): String = targetResolver.apply(payload, metadata)

    /**
     * Extracts the routing key for the given payload and metadata.
     */
    fun key(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): String? = keyExtractor.apply(payload, metadata)

    /**
     * Builds headers for the given payload and metadata.
     */
    fun headers(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): Map<String, String> = headersProvider.apply(payload, metadata)

    /**
     * Maps the payload to a different representation.
     */
    fun mapping(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): Any = payloadMapper.apply(payload, metadata)

    /**
     * Tests if the payload should be externalized.
     * Returns true if the payload passes the filter, false to skip externalization.
     */
    fun filter(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): Boolean = filterPredicate.test(payload, metadata)

    /**
     * Tests if this route matches the given payload and metadata.
     */
    fun matches(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): Boolean = selector.matches(payload, metadata)

    /**
     * Builder for creating [OutboxRoute] instances.
     *
     * Provides a fluent API for both Kotlin and Java to define how outbox payloads
     * are routed to external systems.
     *
     * @author Roland Beisel
     * @since 1.1.0
     */
    class Builder(
        private val selector: OutboxPayloadSelector,
    ) {
        private var targetResolver: BiFunction<Any, OutboxRecordMetadata, String>? = null
        private var keyExtractor: BiFunction<Any, OutboxRecordMetadata, String?> =
            BiFunction { _, metadata -> metadata.key }
        private var headersProvider: BiFunction<Any, OutboxRecordMetadata, Map<String, String>> =
            BiFunction { _, _ -> emptyMap() }
        private var payloadMapper: BiFunction<Any, OutboxRecordMetadata, Any> =
            BiFunction { payload, _ -> payload }
        private var filterPredicate: BiPredicate<Any, OutboxRecordMetadata> =
            BiPredicate { _, _ -> true }

        /**
         * Sets a static target destination.
         */
        fun target(target: String) {
            this.targetResolver = BiFunction { _, _ -> target }
        }

        /**
         * Sets a dynamic target resolver.
         */
        @JvmSynthetic
        fun target(resolver: (Any, OutboxRecordMetadata) -> String) {
            this.targetResolver = BiFunction { payload, metadata -> resolver(payload, metadata) }
        }

        /**
         * Sets a dynamic target resolver (Java-friendly).
         */
        fun target(resolver: BiFunction<Any, OutboxRecordMetadata, String>) {
            this.targetResolver = resolver
        }

        /**
         * Sets a key extractor.
         */
        @JvmSynthetic
        fun key(extractor: (Any, OutboxRecordMetadata) -> String?) {
            this.keyExtractor = BiFunction { payload, metadata -> extractor(payload, metadata) }
        }

        /**
         * Sets a key extractor (Java-friendly).
         */
        fun key(extractor: BiFunction<Any, OutboxRecordMetadata, String?>) {
            this.keyExtractor = extractor
        }

        /**
         * Adds a single static header. Header is merged with any existing headers.
         */
        fun header(
            key: String,
            value: String,
        ) {
            val existing = this.headersProvider
            this.headersProvider =
                BiFunction { payload, metadata ->
                    existing.apply(payload, metadata) + (key to value)
                }
        }

        /**
         * Adds a single dynamic header. Header is merged with any existing headers.
         */
        @JvmSynthetic
        fun header(
            key: String,
            valueResolver: (Any, OutboxRecordMetadata) -> String,
        ) {
            val existing = this.headersProvider
            this.headersProvider =
                BiFunction { payload, metadata ->
                    existing.apply(payload, metadata) + (key to valueResolver(payload, metadata))
                }
        }

        /**
         * Adds a single dynamic header (Java-friendly). Header is merged with any existing headers.
         */
        fun header(
            key: String,
            valueResolver: BiFunction<Any, OutboxRecordMetadata, String>,
        ) {
            val existing = this.headersProvider
            this.headersProvider =
                BiFunction { payload, metadata ->
                    existing.apply(payload, metadata) + (key to valueResolver.apply(payload, metadata))
                }
        }

        /**
         * Sets a headers provider. Headers are merged with any existing headers.
         */
        @JvmSynthetic
        fun headers(provider: (Any, OutboxRecordMetadata) -> Map<String, String>) {
            val existing = this.headersProvider
            this.headersProvider =
                BiFunction { payload, metadata ->
                    existing.apply(payload, metadata) + provider(payload, metadata)
                }
        }

        /**
         * Sets a headers provider (Java-friendly). Headers are merged with any existing headers.
         */
        fun headers(provider: BiFunction<Any, OutboxRecordMetadata, Map<String, String>>) {
            val existing = this.headersProvider
            this.headersProvider =
                BiFunction { payload, metadata ->
                    existing.apply(payload, metadata) + provider.apply(payload, metadata)
                }
        }

        /**
         * Sets a payload mapper.
         */
        @JvmSynthetic
        fun mapping(mapper: (Any, OutboxRecordMetadata) -> Any) {
            this.payloadMapper = BiFunction { payload, metadata -> mapper(payload, metadata) }
        }

        /**
         * Sets a payload mapper (Java-friendly).
         */
        fun mapping(mapper: BiFunction<Any, OutboxRecordMetadata, Any>) {
            this.payloadMapper = mapper
        }

        /**
         * Sets a filter predicate. If the predicate returns false, the payload is not externalized.
         */
        @JvmSynthetic
        fun filter(predicate: (Any, OutboxRecordMetadata) -> Boolean) {
            this.filterPredicate = BiPredicate { payload, metadata -> predicate(payload, metadata) }
        }

        /**
         * Sets a filter predicate (Java-friendly). If the predicate returns false, the payload is not externalized.
         */
        fun filter(predicate: BiPredicate<Any, OutboxRecordMetadata>) {
            this.filterPredicate = predicate
        }

        /**
         * Builds the route.
         *
         * @throws IllegalStateException if target is not configured
         */
        fun build(): OutboxRoute {
            val target =
                targetResolver
                    ?: throw IllegalStateException("Target must be configured")

            return OutboxRoute(
                selector = selector,
                targetResolver = target,
                keyExtractor = keyExtractor,
                headersProvider = headersProvider,
                payloadMapper = payloadMapper,
                filterPredicate = filterPredicate,
            )
        }
    }
}
