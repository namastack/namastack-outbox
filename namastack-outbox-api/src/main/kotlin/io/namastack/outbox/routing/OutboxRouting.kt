package io.namastack.outbox.routing

import io.namastack.outbox.handler.OutboxRecordMetadata

/**
 * Abstract base class for routing outbox events to external systems.
 *
 * Provides methods to resolve targets, extract keys, build headers,
 * map payloads, and filter events based on configured routing rules.
 *
 * This class is extended by broker-specific implementations:
 * - `KafkaOutboxRouting` - for Kafka integration
 * - `RabbitOutboxRouting` - for RabbitMQ integration
 * - `SnsOutboxRouting` - for SNS integration
 *
 * See the concrete implementations for usage examples.
 *
 * ## Available Route Configuration Options
 *
 * | Method | Description |
 * |--------|-------------|
 * | `target(String)` | Static target destination |
 * | `target((Any, OutboxRecordMetadata) -> String)` | Dynamic target resolver |
 * | `key((Any, OutboxRecordMetadata) -> String?)` | Routing/partition key extractor |
 * | `headers((Any, OutboxRecordMetadata) -> Map<String, String>)` | Headers/attributes provider |
 * | `mapping((Any, OutboxRecordMetadata) -> Any)` | Payload transformer |
 * | `filter((Any, OutboxRecordMetadata) -> Boolean)` | Predicate to skip externalization |
 *
 * @param rules Routes evaluated in declaration order; the first matching selector wins
 * @param defaultRule Fallback route used when no selector matches, or `null` when none is configured
 * @author Roland Beisel
 * @since 1.1.0
 */
abstract class OutboxRouting(
    private val rules: List<OutboxRoute>,
    private val defaultRule: OutboxRoute?,
) {
    /**
     * Selects the first route whose selector matches the payload and metadata.
     *
     * Routes are evaluated in declaration order. If none matches, the default route is returned
     * without evaluating its selector. Route filters are evaluated separately by [shouldExternalize].
     *
     * @param payload Original outbox payload used for route selection
     * @param metadata Metadata supplied to route selectors
     * @return The first matching route, the default route, or `null` when neither is available
     */
    fun findRule(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): OutboxRoute? {
        for (rule in rules) {
            if (rule.matches(payload, metadata)) {
                return rule
            }
        }

        return defaultRule
    }

    /**
     * Resolves the destination using the route selected by [findRule].
     *
     * The destination can be a Kafka topic, RabbitMQ exchange, or SNS topic ARN, depending on
     * the integration. Resolving a destination does not evaluate the route's filter.
     *
     * @param payload Original outbox payload used for route selection and target resolution
     * @param metadata Metadata supplied to route selectors and the target resolver
     * @return The selected route's resolved destination
     * @throws IllegalStateException if no route matches and no default route is configured
     */
    fun resolveTarget(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): String =
        findRule(payload, metadata)?.target(payload, metadata)
            ?: throw IllegalStateException("No routing rule found for payload type: ${payload::class.java.name}")

    /**
     * Extracts the routing or partition key using the route selected by [findRule].
     *
     * Routes use [OutboxRecordMetadata.key] unless a custom key extractor is configured.
     * This key controls routing or ordering independently of the idempotency header.
     *
     * @param payload Original outbox payload used for route selection and key extraction
     * @param metadata Metadata supplied to route selectors and the key extractor
     * @return The extracted key, or `null` if the extractor returns `null` or no route is available
     */
    fun extractKey(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): String? = findRule(payload, metadata)?.key(payload, metadata)

    /**
     * Builds message headers or attributes using the route selected by [findRule].
     *
     * Returns only the selected route's configured headers. This overload does not add or validate
     * an idempotency key. A matching route does not inherit headers from the default route.
     *
     * @param payload Original outbox payload used for route selection and header resolution
     * @param metadata Metadata supplied to route selectors and header providers
     * @return The configured headers, or an empty map if none are configured or no route is available
     */
    fun buildHeaders(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): Map<String, String> = findRule(payload, metadata)?.headers(payload, metadata) ?: emptyMap()

    /**
     * Builds message headers or attributes with an optional consumer-facing idempotency key.
     *
     * Resolves the selected route's headers before applying the idempotency header setting. When enabled,
     * an explicit [OutboxHeaders.IDEMPOTENCY_KEY] header takes precedence over
     * [OutboxRecordMetadata.recordId]. A blank explicit value is rejected rather than replaced.
     * The record ID is also used when no route is available. Other headers are preserved.
     *
     * When disabled, returns the configured headers without adding or validating an idempotency key.
     * This method does not evaluate the route's filter or enable native broker deduplication.
     *
     * @param payload Original outbox payload used for route selection and header resolution
     * @param metadata Metadata supplied to route selectors, header providers, and the record ID fallback
     * @param idempotencyHeaderEnabled Whether to add and validate the idempotency header
     * @return The configured headers, including the resolved idempotency key when enabled
     * @throws IllegalArgumentException if header generation is enabled and the key is missing, empty or whitespace-only
     */
    fun buildHeaders(
        payload: Any,
        metadata: OutboxRecordMetadata,
        idempotencyHeaderEnabled: Boolean,
    ): Map<String, String> {
        val headers = buildHeaders(payload, metadata)
        if (!idempotencyHeaderEnabled) return headers

        val key =
            requireNotNull(headers[OutboxHeaders.IDEMPOTENCY_KEY] ?: metadata.recordId) {
                "${OutboxHeaders.IDEMPOTENCY_KEY} requires an explicit key or a recordId"
            }
        require(key.isNotBlank()) { "${OutboxHeaders.IDEMPOTENCY_KEY} must not be empty or whitespace-only" }

        return headers + (OutboxHeaders.IDEMPOTENCY_KEY to key)
    }

    /**
     * Maps the payload to a different representation before sending.
     *
     * Uses the mapper of the route selected by [findRule]. Routes preserve the original payload
     * unless a custom mapper is configured. Mapping does not evaluate the route's filter.
     *
     * @param payload Original outbox payload used for route selection and mapping
     * @param metadata Metadata supplied to route selectors and the payload mapper
     * @return The mapped payload, or the original payload if no route is available
     */
    fun mapPayload(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): Any = findRule(payload, metadata)?.mapping(payload, metadata) ?: payload

    /**
     * Checks whether the selected route allows the payload to be sent to an external system.
     *
     * Evaluates the filter of the route selected by [findRule]. Routes allow all payloads unless
     * a custom filter is configured. If the selected route rejects the payload, subsequent routes
     * and the default route are not evaluated as alternatives.
     *
     * @param payload Original outbox payload used for route selection and filtering
     * @param metadata Metadata supplied to route selectors and the filter predicate
     * @return `true` if a route is available and its filter accepts the payload; `false` otherwise
     */
    fun shouldExternalize(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): Boolean = findRule(payload, metadata)?.filter(payload, metadata) ?: false
}
