package io.namastack.outbox.routing

import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.routing.selector.OutboxPayloadSelector
import java.util.function.BiFunction
import java.util.function.BiPredicate

/**
 * DSL builder for a routing rule.
 *
 * @author Roland Beisel
 * @since 1.1.0
 */
class OutboxRouteBuilder(
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
     * Adds a single static header.
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
     * Adds a single dynamic header.
     */
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
     * Adds a single dynamic header (Java-friendly).
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

    /**
     * Sets a payload mapper.
     */
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
    fun filter(predicate: (Any, OutboxRecordMetadata) -> Boolean) {
        this.filterPredicate = BiPredicate { payload, metadata -> predicate(payload, metadata) }
    }

    /**
     * Sets a filter predicate (Java-friendly). If the predicate returns false, the payload is not externalized.
     */
    fun filter(predicate: BiPredicate<Any, OutboxRecordMetadata>) {
        this.filterPredicate = predicate
    }

    fun build(): OutboxRoutingRule {
        val target =
            targetResolver
                ?: throw IllegalStateException("Target must be configured")

        return OutboxRoutingRule
            .builder(selector)
            .target(target)
            .key(keyExtractor)
            .headers(headersProvider)
            .mapping(payloadMapper)
            .filter(filterPredicate)
            .build()
    }
}
