package io.namastack.outbox.kafka

import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.routing.OutboxRouteBuilder
import java.util.function.BiFunction

/**
 * Kafka-specific route builder that wraps [OutboxRouteBuilder]
 * and provides `topic()` as Kafka-specific terminology for `target()`.
 *
 * @author Roland Beisel
 * @since 1.1.0
 */
class KafkaRouteBuilder internal constructor(
    private val delegate: OutboxRouteBuilder,
) {
    /**
     * Sets a static Kafka topic.
     */
    fun topic(topic: String) {
        delegate.target(topic)
    }

    /**
     * Sets a dynamic Kafka topic resolver.
     */
    fun topic(resolver: (Any, OutboxRecordMetadata) -> String) {
        delegate.target(resolver)
    }

    /**
     * Sets a dynamic Kafka topic resolver (Java-friendly).
     */
    fun topic(resolver: BiFunction<Any, OutboxRecordMetadata, String>) {
        delegate.target(resolver)
    }

    /**
     * Sets a key extractor for Kafka message partitioning.
     */
    fun key(extractor: (Any, OutboxRecordMetadata) -> String?) {
        delegate.key(extractor)
    }

    /**
     * Sets a key extractor for Kafka message partitioning (Java-friendly).
     */
    fun key(extractor: BiFunction<Any, OutboxRecordMetadata, String?>) {
        delegate.key(extractor)
    }

    /**
     * Sets a Kafka headers provider.
     */
    fun headers(provider: (Any, OutboxRecordMetadata) -> Map<String, String>) {
        delegate.headers(provider)
    }

    /**
     * Sets a Kafka headers provider (Java-friendly).
     */
    fun headers(provider: BiFunction<Any, OutboxRecordMetadata, Map<String, String>>) {
        delegate.headers(provider)
    }
}
