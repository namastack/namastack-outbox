package io.namastack.outbox.kafka

import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.routing.selector.OutboxPayloadSelector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.function.BiFunction

/**
 * Tests for [KafkaRouteBuilder].
 *
 * These tests verify the Kafka-specific DSL methods (topic, key, headers)
 * through the full configuration flow.
 */
class KafkaRouteBuilderTest {
    private val metadata =
        OutboxRecordMetadata(
            key = "test-key",
            handlerId = "test-handler",
            createdAt = Instant.now(),
            context = mapOf("tenant" to "acme"),
        )

    @Test
    fun `topic with static string sets target`() {
        val config =
            KafkaRoutingConfiguration
                .create()
                .routing {
                    route(OutboxPayloadSelector.predicate { _, _ -> true }) {
                        topic("my-topic")
                    }
                }

        assertThat(config.resolveTopic("payload", metadata)).isEqualTo("my-topic")
    }

    @Test
    fun `topic with lambda sets dynamic target`() {
        val config =
            KafkaRoutingConfiguration
                .create()
                .routing {
                    route(OutboxPayloadSelector.predicate { _, _ -> true }) {
                        topic { payload, _ -> "topic-$payload" }
                    }
                }

        assertThat(config.resolveTopic("test", metadata)).isEqualTo("topic-test")
    }

    @Test
    fun `topic with BiFunction sets dynamic target`() {
        val config =
            KafkaRoutingConfiguration
                .create()
                .routing {
                    route(OutboxPayloadSelector.predicate { _, _ -> true }) {
                        topic(BiFunction { payload, _ -> "topic-$payload" })
                    }
                }

        assertThat(config.resolveTopic("test", metadata)).isEqualTo("topic-test")
    }

    @Test
    fun `key with lambda sets key extractor`() {
        val config =
            KafkaRoutingConfiguration
                .create()
                .routing {
                    route(OutboxPayloadSelector.predicate { _, _ -> true }) {
                        topic("test")
                        key { payload, _ -> "key-$payload" }
                    }
                }

        assertThat(config.extractKey("test", metadata)).isEqualTo("key-test")
    }

    @Test
    fun `key with BiFunction sets key extractor`() {
        val config =
            KafkaRoutingConfiguration
                .create()
                .routing {
                    route(OutboxPayloadSelector.predicate { _, _ -> true }) {
                        topic("test")
                        key(BiFunction { _, meta -> meta.key })
                    }
                }

        assertThat(config.extractKey("payload", metadata)).isEqualTo("test-key")
    }

    @Test
    fun `key defaults to metadata key when not set`() {
        val config =
            KafkaRoutingConfiguration
                .create()
                .routing {
                    route(OutboxPayloadSelector.predicate { _, _ -> true }) {
                        topic("test")
                    }
                }

        assertThat(config.extractKey("payload", metadata)).isEqualTo("test-key")
    }

    @Test
    fun `headers with lambda sets headers provider`() {
        val config =
            KafkaRoutingConfiguration
                .create()
                .routing {
                    route(OutboxPayloadSelector.predicate { _, _ -> true }) {
                        topic("test")
                        headers { _, meta -> meta.context }
                    }
                }

        assertThat(config.buildHeaders("payload", metadata)).containsEntry("tenant", "acme")
    }

    @Test
    fun `headers with BiFunction sets headers provider`() {
        val config =
            KafkaRoutingConfiguration
                .create()
                .routing {
                    route(OutboxPayloadSelector.predicate { _, _ -> true }) {
                        topic("test")
                        headers(BiFunction { payload, _ -> mapOf("payload" to payload.toString()) })
                    }
                }

        assertThat(config.buildHeaders("test", metadata)).containsEntry("payload", "test")
    }

    @Test
    fun `headers defaults to empty map when not set`() {
        val config =
            KafkaRoutingConfiguration
                .create()
                .routing {
                    route(OutboxPayloadSelector.predicate { _, _ -> true }) {
                        topic("test")
                    }
                }

        assertThat(config.buildHeaders("payload", metadata)).isEmpty()
    }
}
