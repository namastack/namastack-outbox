package io.namastack.outbox.routing

import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.routing.selector.OutboxPayloadSelector
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.function.BiFunction
import java.util.function.BiPredicate

class OutboxRouteTest {
    private val metadata =
        OutboxRecordMetadata(
            key = "test-key",
            handlerId = "test-handler",
            createdAt = Instant.now(),
            context = mapOf("tenant" to "acme"),
        )

    private val selector = OutboxPayloadSelector.predicate { _, _ -> true }

    @Nested
    inner class FactoryMethod {
        @Test
        fun `builder with DSL creates route`() {
            val route =
                OutboxRoute.builder(OutboxPayloadSelector.type(String::class.java)) {
                    target("my-topic")
                }

            assertThat(route.target("payload", metadata)).isEqualTo("my-topic")
        }

        @Test
        fun `builder with Consumer creates route for Java`() {
            val route =
                OutboxRoute.builder(OutboxPayloadSelector.type(String::class.java)) { builder ->
                    builder.target("my-topic")
                }

            assertThat(route.target("payload", metadata)).isEqualTo("my-topic")
        }
    }

    @Nested
    inner class Target {
        @Test
        fun `static string sets target`() {
            val route =
                OutboxRoute
                    .Builder(selector)
                    .apply { target("my-target") }
                    .build()

            assertThat(route.target("payload", metadata)).isEqualTo("my-target")
        }

        @Test
        fun `lambda sets dynamic target`() {
            val route =
                OutboxRoute
                    .Builder(selector)
                    .apply { target { payload, _ -> "target-$payload" } }
                    .build()

            assertThat(route.target("test", metadata)).isEqualTo("target-test")
        }

        @Test
        fun `BiFunction sets dynamic target for Java`() {
            val route =
                OutboxRoute
                    .Builder(selector)
                    .apply { target(BiFunction { payload, _ -> "target-$payload" }) }
                    .build()

            assertThat(route.target("test", metadata)).isEqualTo("target-test")
        }

        @Test
        fun `throws when not configured`() {
            val builder = OutboxRoute.Builder(selector)

            assertThatThrownBy { builder.build() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Target must be configured")
        }
    }

    @Nested
    inner class Key {
        @Test
        fun `defaults to metadata key`() {
            val route =
                OutboxRoute
                    .Builder(selector)
                    .apply { target("test") }
                    .build()

            assertThat(route.key("payload", metadata)).isEqualTo("test-key")
        }

        @Test
        fun `lambda sets key extractor`() {
            val route =
                OutboxRoute
                    .Builder(selector)
                    .apply {
                        target("test")
                        key { payload, _ -> "key-$payload" }
                    }.build()

            assertThat(route.key("test", metadata)).isEqualTo("key-test")
        }

        @Test
        fun `BiFunction sets key extractor for Java`() {
            val route =
                OutboxRoute
                    .Builder(selector)
                    .apply {
                        target("test")
                        key(BiFunction { _, meta -> "key-${meta.key}" })
                    }.build()

            assertThat(route.key("payload", metadata)).isEqualTo("key-test-key")
        }

        @Test
        fun `can return null`() {
            val route =
                OutboxRoute
                    .Builder(selector)
                    .apply {
                        target("test")
                        key { _, _ -> null }
                    }.build()

            assertThat(route.key("payload", metadata)).isNull()
        }
    }

    @Nested
    inner class Headers {
        @Test
        fun `defaults to empty map`() {
            val route =
                OutboxRoute
                    .Builder(selector)
                    .apply { target("test") }
                    .build()

            assertThat(route.headers("payload", metadata)).isEmpty()
        }

        @Test
        fun `static header adds single header`() {
            val route =
                OutboxRoute
                    .Builder(selector)
                    .apply {
                        target("test")
                        header("key1", "value1")
                    }.build()

            assertThat(route.headers("payload", metadata))
                .containsEntry("key1", "value1")
        }

        @Test
        fun `multiple static headers are accumulated`() {
            val route =
                OutboxRoute
                    .Builder(selector)
                    .apply {
                        target("test")
                        header("key1", "value1")
                        header("key2", "value2")
                    }.build()

            assertThat(route.headers("payload", metadata))
                .containsEntry("key1", "value1")
                .containsEntry("key2", "value2")
        }

        @Test
        fun `dynamic header with lambda`() {
            val route =
                OutboxRoute
                    .Builder(selector)
                    .apply {
                        target("test")
                        header("dynamic") { payload, _ -> "value-$payload" }
                    }.build()

            assertThat(route.headers("test", metadata))
                .containsEntry("dynamic", "value-test")
        }

        @Test
        fun `dynamic header with BiFunction for Java`() {
            val route =
                OutboxRoute
                    .Builder(selector)
                    .apply {
                        target("test")
                        header("dynamic", BiFunction { payload, _ -> "value-$payload" })
                    }.build()

            assertThat(route.headers("test", metadata))
                .containsEntry("dynamic", "value-test")
        }

        @Test
        fun `headers provider with lambda`() {
            val route =
                OutboxRoute
                    .Builder(selector)
                    .apply {
                        target("test")
                        headers { _, meta -> meta.context }
                    }.build()

            assertThat(route.headers("payload", metadata))
                .containsEntry("tenant", "acme")
        }

        @Test
        fun `headers provider with BiFunction for Java`() {
            val route =
                OutboxRoute
                    .Builder(selector)
                    .apply {
                        target("test")
                        headers(BiFunction { payload, _ -> mapOf("payload" to payload.toString()) })
                    }.build()

            assertThat(route.headers("test", metadata))
                .containsEntry("payload", "test")
        }

        @Test
        fun `header adds to existing headers from provider`() {
            val route =
                OutboxRoute
                    .Builder(selector)
                    .apply {
                        target("test")
                        headers { _, _ -> mapOf("base" to "value") }
                        header("extra", "added")
                    }.build()

            assertThat(route.headers("payload", metadata))
                .containsEntry("base", "value")
                .containsEntry("extra", "added")
        }

        @Test
        fun `multiple mixed header calls accumulate`() {
            val route =
                OutboxRoute
                    .Builder(selector)
                    .apply {
                        target("test")
                        header("static", "value1")
                        header("lambda") { _, _ -> "value2" }
                        header("bifunction", BiFunction { _, _ -> "value3" })
                    }.build()

            assertThat(route.headers("payload", metadata))
                .containsEntry("static", "value1")
                .containsEntry("lambda", "value2")
                .containsEntry("bifunction", "value3")
                .hasSize(3)
        }
    }

    @Nested
    inner class Mapping {
        @Test
        fun `defaults to identity`() {
            val route =
                OutboxRoute
                    .Builder(selector)
                    .apply { target("test") }
                    .build()

            val payload = "original-payload"
            assertThat(route.mapping(payload, metadata)).isSameAs(payload)
        }

        @Test
        fun `lambda sets payload mapper`() {
            val route =
                OutboxRoute
                    .Builder(selector)
                    .apply {
                        target("test")
                        mapping { payload, _ -> (payload as String).uppercase() }
                    }.build()

            assertThat(route.mapping("hello", metadata)).isEqualTo("HELLO")
        }

        @Test
        fun `BiFunction sets payload mapper for Java`() {
            val route =
                OutboxRoute
                    .Builder(selector)
                    .apply {
                        target("test")
                        mapping(BiFunction { payload, _ -> (payload as String).uppercase() })
                    }.build()

            assertThat(route.mapping("hello", metadata)).isEqualTo("HELLO")
        }

        @Test
        fun `can access metadata`() {
            val route =
                OutboxRoute
                    .Builder(selector)
                    .apply {
                        target("test")
                        mapping { payload, meta -> "$payload-${meta.key}" }
                    }.build()

            assertThat(route.mapping("value", metadata)).isEqualTo("value-test-key")
        }

        @Test
        fun `can transform to different type`() {
            data class MappedPayload(
                val value: String,
                val key: String,
            )

            val route =
                OutboxRoute
                    .Builder(selector)
                    .apply {
                        target("test")
                        mapping { payload, meta ->
                            MappedPayload(
                                value = payload as String,
                                key = meta.key,
                            )
                        }
                    }.build()

            val mapped = route.mapping("test-value", metadata) as MappedPayload
            assertThat(mapped.value).isEqualTo("test-value")
            assertThat(mapped.key).isEqualTo("test-key")
        }
    }

    @Nested
    inner class Filter {
        @Test
        fun `defaults to true`() {
            val route =
                OutboxRoute
                    .Builder(selector)
                    .apply { target("test") }
                    .build()

            assertThat(route.filter("payload", metadata)).isTrue()
        }

        @Test
        fun `lambda sets filter predicate`() {
            val route =
                OutboxRoute
                    .Builder(selector)
                    .apply {
                        target("test")
                        filter { payload, _ -> (payload as String).startsWith("valid") }
                    }.build()

            assertThat(route.filter("valid-payload", metadata)).isTrue()
            assertThat(route.filter("invalid-payload", metadata)).isFalse()
        }

        @Test
        fun `BiPredicate sets filter predicate for Java`() {
            val route =
                OutboxRoute
                    .Builder(selector)
                    .apply {
                        target("test")
                        filter(BiPredicate { _, meta -> meta.context["tenant"] == "acme" })
                    }.build()

            assertThat(route.filter("payload", metadata)).isTrue()

            val otherMetadata =
                OutboxRecordMetadata(
                    key = "test-key",
                    handlerId = "test-handler",
                    createdAt = Instant.now(),
                    context = mapOf("tenant" to "other"),
                )
            assertThat(route.filter("payload", otherMetadata)).isFalse()
        }

        @Test
        fun `can access both payload and metadata`() {
            val route =
                OutboxRoute
                    .Builder(selector)
                    .apply {
                        target("test")
                        filter { payload, meta ->
                            (payload as String).isNotEmpty() && meta.key == "test-key"
                        }
                    }.build()

            assertThat(route.filter("valid", metadata)).isTrue()
            assertThat(route.filter("", metadata)).isFalse()
        }
    }

    @Nested
    inner class Matches {
        @Test
        fun `delegates to selector`() {
            val route =
                OutboxRoute.builder(OutboxPayloadSelector.type(String::class.java)) {
                    target("test")
                }

            assertThat(route.matches("string", metadata)).isTrue()
            assertThat(route.matches(123, metadata)).isFalse()
        }
    }

    @Nested
    inner class FullyConfiguredRoute {
        @Test
        fun `all options work together`() {
            val route =
                OutboxRoute.builder(selector) {
                    target { payload, _ -> "target-$payload" }
                    key { payload, _ -> "key-$payload" }
                    headers { _, meta -> meta.context }
                    mapping { payload, _ -> (payload as String).uppercase() }
                    filter { payload, _ -> (payload as String).isNotEmpty() }
                }

            assertThat(route.target("test", metadata)).isEqualTo("target-test")
            assertThat(route.key("test", metadata)).isEqualTo("key-test")
            assertThat(route.headers("test", metadata)).containsEntry("tenant", "acme")
            assertThat(route.mapping("test", metadata)).isEqualTo("TEST")
            assertThat(route.filter("test", metadata)).isTrue()
            assertThat(route.filter("", metadata)).isFalse()
        }
    }
}
