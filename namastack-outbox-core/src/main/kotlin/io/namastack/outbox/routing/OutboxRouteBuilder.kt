package io.namastack.outbox.routing

import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.routing.selector.OutboxPayloadSelector
import java.util.function.BiFunction

/**
 * DSL builder for a routing rule.
 *
 * @author Roland Beisel
 * @since 1.1.0
 */
class OutboxRouteBuilder internal constructor(
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
    fun target(target: String) {
        this.targetResolver = BiFunction { _, _ -> target }
    }

    /**
     * Sets a dynamic target resolver.
     */
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
     * Sets a headers provider.
     */
    fun headers(provider: (Any, OutboxRecordMetadata) -> Map<String, String>) {
        this.headersProvider = BiFunction { payload, metadata -> provider(payload, metadata) }
    }

    /**
     * Sets a headers provider (Java-friendly).
     */
    fun headers(provider: BiFunction<Any, OutboxRecordMetadata, Map<String, String>>) {
        this.headersProvider = provider
    }

    internal fun build(): OutboxRoutingRule {
        val target =
            targetResolver
                ?: throw IllegalStateException("Target must be configured")

        return OutboxRoutingRule
            .builder(selector)
            .target(target)
            .key(keyExtractor)
            .headers(headersProvider)
            .build()
    }
}
