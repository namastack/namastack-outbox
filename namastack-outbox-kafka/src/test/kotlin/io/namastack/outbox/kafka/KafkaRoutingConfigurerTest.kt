package io.namastack.outbox.kafka

import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.routing.selector.OutboxPayloadSelector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.function.Consumer

/**
 * Tests for [KafkaRoutingConfigurer].
 *
 * These tests verify the route() and defaults() DSL methods
 * through the full configuration flow.
 */
class KafkaRoutingConfigurerTest {
    private val metadata =
        OutboxRecordMetadata(
            key = "test-key",
            handlerId = "test-handler",
            createdAt = Instant.now(),
            context = emptyMap(),
        )

    @Test
    fun `route adds rule with selector and topic`() {
        val config =
            KafkaRoutingConfiguration
                .create()
                .routing {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        topic("strings")
                    }
                }

        assertThat(config.resolveTopic("test", metadata)).isEqualTo("strings")
    }

    @Test
    fun `route with Consumer adds rule`() {
        val config =
            KafkaRoutingConfiguration
                .create()
                .routing {
                    route(
                        OutboxPayloadSelector.type(String::class.java),
                        Consumer { it.topic("strings") },
                    )
                }

        assertThat(config.resolveTopic("test", metadata)).isEqualTo("strings")
    }

    @Test
    fun `multiple routes are added in order - first match wins`() {
        val config =
            KafkaRoutingConfiguration
                .create()
                .routing {
                    route(OutboxPayloadSelector.predicate { _, _ -> true }) {
                        topic("first")
                    }
                    route(OutboxPayloadSelector.predicate { _, _ -> true }) {
                        topic("second")
                    }
                }

        // First match wins
        assertThat(config.resolveTopic("test", metadata)).isEqualTo("first")
    }

    @Test
    fun `defaults sets default rule`() {
        val config =
            KafkaRoutingConfiguration
                .create()
                .routing {
                    route(OutboxPayloadSelector.type(Int::class.java)) {
                        topic("ints")
                    }
                    defaults {
                        topic("default-topic")
                    }
                }

        // String doesn't match Int, so default is used
        assertThat(config.resolveTopic("string", metadata)).isEqualTo("default-topic")
    }

    @Test
    fun `defaults with Consumer sets default rule`() {
        val config =
            KafkaRoutingConfiguration
                .create()
                .routing {
                    route(OutboxPayloadSelector.type(Int::class.java)) {
                        topic("ints")
                    }
                    defaults(Consumer { it.topic("default-topic") })
                }

        assertThat(config.resolveTopic("string", metadata)).isEqualTo("default-topic")
    }

    @Test
    fun `route configures key extractor`() {
        val config =
            KafkaRoutingConfiguration
                .create()
                .routing {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        topic("strings")
                        key { payload, _ -> "key-$payload" }
                    }
                }

        assertThat(config.extractKey("test", metadata)).isEqualTo("key-test")
    }

    @Test
    fun `route configures headers provider`() {
        val config =
            KafkaRoutingConfiguration
                .create()
                .routing {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        topic("strings")
                        headers { payload, _ -> mapOf("payload" to payload.toString()) }
                    }
                }

        assertThat(config.buildHeaders("test", metadata)).containsEntry("payload", "test")
    }

    @Test
    fun `defaults rule also supports key and headers`() {
        val config =
            KafkaRoutingConfiguration
                .create()
                .routing {
                    defaults {
                        topic("default")
                        key { _, meta -> "default-${meta.key}" }
                        headers { _, _ -> mapOf("default" to "true") }
                    }
                }

        assertThat(config.resolveTopic("any", metadata)).isEqualTo("default")
        assertThat(config.extractKey("any", metadata)).isEqualTo("default-test-key")
        assertThat(config.buildHeaders("any", metadata)).containsEntry("default", "true")
    }
}
