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
 * @author Roland Beisel
 * @since 1.1.0
 */
abstract class OutboxRouting(
    private val configurer: OutboxRoutingConfigurer,
) {
    /**
     * Resolves the target destination for a given payload and metadata.
     *
     * @throws IllegalStateException if no matching route is found
     */
    fun resolveTarget(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): String = configurer.resolveTarget(payload, metadata)

    /**
     * Extracts the routing key for a given payload and metadata.
     */
    fun extractKey(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): String? = configurer.extractKey(payload, metadata)

    /**
     * Builds headers/attributes for a given payload and metadata.
     */
    fun buildHeaders(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): Map<String, String> = configurer.buildHeaders(payload, metadata)

    /**
     * Maps the payload to a different representation before sending.
     */
    fun mapPayload(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): Any = configurer.findRule(payload, metadata)?.mapping(payload, metadata) ?: payload

    /**
     * Checks if the payload should be externalized.
     * Returns false if the payload is filtered out.
     */
    fun shouldExternalize(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): Boolean = configurer.findRule(payload, metadata)?.filter(payload, metadata) ?: true
}
