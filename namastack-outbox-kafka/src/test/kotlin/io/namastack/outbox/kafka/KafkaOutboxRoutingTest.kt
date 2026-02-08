package io.namastack.outbox.kafka

import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.routing.selector.OutboxPayloadSelector
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

class KafkaOutboxRoutingTest {
    private val metadata =
        OutboxRecordMetadata(
            key = "order-123",
            handlerId = "test-handler",
            createdAt = Instant.now(),
            context = mapOf("tenant" to "acme"),
        )

    @Nested
    inner class KotlinDsl {
        @Test
        fun `kafkaOutboxRouting creates routing with routes`() {
            val routing =
                kafkaOutboxRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target("strings")
                    }
                }

            assertThat(routing.resolveTopic("test", metadata)).isEqualTo("strings")
        }

        @Test
        fun `kafkaOutboxRouting creates routing with defaults`() {
            val routing =
                kafkaOutboxRouting {
                    defaults {
                        target("default-topic")
                    }
                }

            assertThat(routing.resolveTopic("any-payload", metadata)).isEqualTo("default-topic")
        }

        @Test
        fun `kafkaOutboxRouting supports all route options`() {
            val routing =
                kafkaOutboxRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target("strings")
                        key { payload, _ -> "key-$payload" }
                        headers { _, meta -> mapOf("tenant" to meta.context["tenant"]!!) }
                        mapping { payload, _ -> (payload as String).uppercase() }
                        filter { payload, _ -> (payload as String).isNotEmpty() }
                    }
                }

            assertThat(routing.resolveTopic("test", metadata)).isEqualTo("strings")
            assertThat(routing.extractKey("test", metadata)).isEqualTo("key-test")
            assertThat(routing.buildHeaders("test", metadata)).containsEntry("tenant", "acme")
            assertThat(routing.mapPayload("test", metadata)).isEqualTo("TEST")
            assertThat(routing.shouldExternalize("test", metadata)).isTrue()
            assertThat(routing.shouldExternalize("", metadata)).isFalse()
        }

        @Test
        fun `kafkaOutboxRouting supports multiple routes`() {
            val routing =
                kafkaOutboxRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target("strings")
                    }
                    route(OutboxPayloadSelector.type(Int::class.javaObjectType)) {
                        target("ints")
                    }
                }

            assertThat(routing.resolveTopic("test", metadata)).isEqualTo("strings")
            assertThat(routing.resolveTopic(123, metadata)).isEqualTo("ints")
        }
    }

    @Nested
    inner class JavaBuilder {
        @Test
        fun `builder creates routing with routes`() {
            val routing =
                KafkaOutboxRouting
                    .builder()
                    .route(OutboxPayloadSelector.type(String::class.java)) { route ->
                        route.target("strings")
                    }.build()

            assertThat(routing.resolveTopic("test", metadata)).isEqualTo("strings")
        }

        @Test
        fun `builder creates routing with defaults`() {
            val routing =
                KafkaOutboxRouting
                    .builder()
                    .defaults { route ->
                        route.target("default-topic")
                    }.build()

            assertThat(routing.resolveTopic("any-payload", metadata)).isEqualTo("default-topic")
        }

        @Test
        fun `builder supports all route options`() {
            val routing =
                KafkaOutboxRouting
                    .builder()
                    .route(OutboxPayloadSelector.type(String::class.java)) { route ->
                        route.target("strings")
                        route.key { payload, _ -> "key-$payload" }
                        route.headers { _, meta -> mapOf("tenant" to meta.context["tenant"]!!) }
                        route.mapping { payload, _ -> (payload as String).uppercase() }
                        route.filter { payload, _ -> (payload as String).isNotEmpty() }
                    }.build()

            assertThat(routing.resolveTopic("test", metadata)).isEqualTo("strings")
            assertThat(routing.extractKey("test", metadata)).isEqualTo("key-test")
            assertThat(routing.buildHeaders("test", metadata)).containsEntry("tenant", "acme")
            assertThat(routing.mapPayload("test", metadata)).isEqualTo("TEST")
            assertThat(routing.shouldExternalize("test", metadata)).isTrue()
            assertThat(routing.shouldExternalize("", metadata)).isFalse()
        }

        @Test
        fun `builder supports method chaining`() {
            val builder = KafkaOutboxRouting.builder()

            val result =
                builder
                    .route(OutboxPayloadSelector.type(String::class.java)) { it.target("strings") }
                    .route(OutboxPayloadSelector.type(Int::class.javaObjectType)) { it.target("ints") }
                    .defaults { it.target("default") }

            assertThat(result).isSameAs(builder)
        }

        @Test
        fun `builder supports multiple routes`() {
            val routing =
                KafkaOutboxRouting
                    .builder()
                    .route(OutboxPayloadSelector.type(String::class.java)) { it.target("strings") }
                    .route(OutboxPayloadSelector.type(Int::class.javaObjectType)) { it.target("ints") }
                    .build()

            assertThat(routing.resolveTopic("test", metadata)).isEqualTo("strings")
            assertThat(routing.resolveTopic(123, metadata)).isEqualTo("ints")
        }
    }

    @Nested
    inner class ResolveTopic {
        @Test
        fun `resolveTopic delegates to resolveTarget`() {
            val routing =
                kafkaOutboxRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target("my-topic")
                    }
                }

            assertThat(routing.resolveTopic("test", metadata)).isEqualTo("my-topic")
            assertThat(routing.resolveTarget("test", metadata)).isEqualTo("my-topic")
        }

        @Test
        fun `resolveTopic throws when no matching route`() {
            val routing =
                kafkaOutboxRouting {
                    route(OutboxPayloadSelector.type(Int::class.javaObjectType)) {
                        target("ints")
                    }
                }

            assertThatThrownBy { routing.resolveTopic("string", metadata) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("No routing rule found")
        }

        @Test
        fun `resolveTopic supports dynamic topic`() {
            val routing =
                kafkaOutboxRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target { payload, _ -> "topic-$payload" }
                    }
                }

            assertThat(routing.resolveTopic("orders", metadata)).isEqualTo("topic-orders")
        }
    }

    @Nested
    inner class RoutePrecedence {
        @Test
        fun `first matching route wins`() {
            val routing =
                kafkaOutboxRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target("first")
                    }
                    route(OutboxPayloadSelector.predicate { _, _ -> true }) {
                        target("second")
                    }
                }

            assertThat(routing.resolveTopic("test", metadata)).isEqualTo("first")
        }

        @Test
        fun `defaults used when no route matches`() {
            val routing =
                kafkaOutboxRouting {
                    route(OutboxPayloadSelector.type(Int::class.javaObjectType)) {
                        target("ints")
                    }
                    defaults {
                        target("default-topic")
                    }
                }

            assertThat(routing.resolveTopic("string", metadata)).isEqualTo("default-topic")
        }

        @Test
        fun `specific route takes precedence over defaults`() {
            val routing =
                kafkaOutboxRouting {
                    defaults {
                        target("default-topic")
                    }
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target("strings")
                    }
                }

            assertThat(routing.resolveTopic("test", metadata)).isEqualTo("strings")
        }
    }

    @Nested
    inner class InheritedMethods {
        @Test
        fun `extractKey works`() {
            val routing =
                kafkaOutboxRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target("strings")
                        key { payload, _ -> "key-$payload" }
                    }
                }

            assertThat(routing.extractKey("test", metadata)).isEqualTo("key-test")
        }

        @Test
        fun `buildHeaders works`() {
            val routing =
                kafkaOutboxRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target("strings")
                        headers { _, meta -> meta.context }
                    }
                }

            assertThat(routing.buildHeaders("test", metadata)).containsEntry("tenant", "acme")
        }

        @Test
        fun `mapPayload works`() {
            val routing =
                kafkaOutboxRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target("strings")
                        mapping { payload, _ -> (payload as String).uppercase() }
                    }
                }

            assertThat(routing.mapPayload("test", metadata)).isEqualTo("TEST")
        }

        @Test
        fun `shouldExternalize works`() {
            val routing =
                kafkaOutboxRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target("strings")
                        filter { payload, _ -> (payload as String) != "skip" }
                    }
                }

            assertThat(routing.shouldExternalize("test", metadata)).isTrue()
            assertThat(routing.shouldExternalize("skip", metadata)).isFalse()
        }
    }
}
