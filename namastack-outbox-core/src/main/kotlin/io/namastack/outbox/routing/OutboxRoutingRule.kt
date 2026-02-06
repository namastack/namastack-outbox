package io.namastack.outbox.routing

import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.routing.selector.OutboxPayloadSelector
import java.util.function.BiFunction

/**
 * A routing rule that defines how outbox payloads are externalized.
 *
 * Combines a [selector] for matching payloads with configuration for:
 * - [target] - destination (Kafka topic, RabbitMQ exchange, SNS topic, etc.)
 * - [key] - routing/partition key
 * - [headers] - message headers/attributes
 *
 * Rules are evaluated in declaration order - first match wins.
 *
 * ## Example (Kotlin)
 *
 * ```kotlin
 * OutboxRoutingRule.builder(OutboxPayloadSelector.type(OrderEvent::class.java))
 *     .target("orders")
 *     .key { payload, metadata -> (payload as OrderEvent).orderId }
 *     .headers { _, metadata -> metadata.context }
 *     .build()
 * ```
 *
 * ## Example (Java)
 *
 * ```java
 * OutboxRoutingRule.builder(OutboxPayloadSelector.type(OrderEvent.class))
 *     .target("orders")
 *     .key((payload, metadata) -> ((OrderEvent) payload).getOrderId())
 *     .build();
 * ```
 *
 * @author Roland Beisel
 * @since 1.1.0
 */
class OutboxRoutingRule private constructor(
    val selector: OutboxPayloadSelector,
    private val targetResolver: BiFunction<Any, OutboxRecordMetadata, String>,
    private val keyExtractor: BiFunction<Any, OutboxRecordMetadata, String?>,
    private val headersProvider: BiFunction<Any, OutboxRecordMetadata, Map<String, String>>,
) {
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

    companion object {
        /**
         * Creates a builder for a routing rule with the given selector.
         */
        @JvmStatic
        fun builder(selector: OutboxPayloadSelector): Builder = Builder(selector)
    }

    /**
     * Builder for [OutboxRoutingRule].
     */
    class Builder(
        private val selector: OutboxPayloadSelector,
    ) {
        private var targetResolver: BiFunction<Any, OutboxRecordMetadata, String>? = null
        private var keyExtractor: BiFunction<Any, OutboxRecordMetadata, String?> =
            BiFunction { _, metadata -> metadata.key }
        private var headersProvider: BiFunction<Any, OutboxRecordMetadata, Map<String, String>> =
            BiFunction { _, _ -> emptyMap() }

        /**
         * Sets a static target destination.
         */
        fun target(target: String): Builder {
            this.targetResolver = BiFunction { _, _ -> target }
            return this
        }

        /**
         * Sets a dynamic target resolver.
         */
        fun target(resolver: (Any, OutboxRecordMetadata) -> String): Builder {
            this.targetResolver = BiFunction { payload, metadata -> resolver(payload, metadata) }
            return this
        }

        /**
         * Sets a dynamic target resolver (Java-friendly).
         */
        fun target(resolver: BiFunction<Any, OutboxRecordMetadata, String>): Builder {
            this.targetResolver = resolver
            return this
        }

        /**
         * Sets a key extractor.
         */
        fun key(extractor: (Any, OutboxRecordMetadata) -> String?): Builder {
            this.keyExtractor = BiFunction { payload, metadata -> extractor(payload, metadata) }
            return this
        }

        /**
         * Sets a key extractor (Java-friendly).
         */
        fun key(extractor: BiFunction<Any, OutboxRecordMetadata, String?>): Builder {
            this.keyExtractor = extractor
            return this
        }

        /**
         * Sets a headers provider.
         */
        fun headers(provider: (Any, OutboxRecordMetadata) -> Map<String, String>): Builder {
            this.headersProvider = BiFunction { payload, metadata -> provider(payload, metadata) }
            return this
        }

        /**
         * Sets a headers provider (Java-friendly).
         */
        fun headers(provider: BiFunction<Any, OutboxRecordMetadata, Map<String, String>>): Builder {
            this.headersProvider = provider
            return this
        }

        /**
         * Builds the routing rule.
         *
         * @throws IllegalStateException if target is not configured
         */
        fun build(): OutboxRoutingRule {
            val target =
                targetResolver
                    ?: throw IllegalStateException("Target must be configured")

            return OutboxRoutingRule(
                selector = selector,
                targetResolver = target,
                keyExtractor = keyExtractor,
                headersProvider = headersProvider,
            )
        }
    }
}
