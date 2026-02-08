package io.namastack.outbox.routing

import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.routing.selector.OutboxPayloadSelector
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.function.BiFunction
import java.util.function.BiPredicate

class OutboxRouteBuilderTest {
    private val metadata =
        OutboxRecordMetadata(
            key = "test-key",
            handlerId = "test-handler",
            createdAt = Instant.now(),
            context = mapOf("tenant" to "acme"),
        )

    private val selector = OutboxPayloadSelector.predicate { _, _ -> true }

    @Test
    fun `target with static string sets target resolver`() {
        val rule =
            OutboxRouteBuilder(selector)
                .apply { target("my-target") }
                .build()

        assertThat(rule.target("payload", metadata)).isEqualTo("my-target")
    }

    @Test
    fun `target with lambda sets dynamic target resolver`() {
        val rule =
            OutboxRouteBuilder(selector)
                .apply { target { payload, _ -> "target-$payload" } }
                .build()

        assertThat(rule.target("test", metadata)).isEqualTo("target-test")
    }

    @Test
    fun `target with BiFunction sets dynamic target resolver`() {
        val rule =
            OutboxRouteBuilder(selector)
                .apply { target(BiFunction { payload, _ -> "target-$payload" }) }
                .build()

        assertThat(rule.target("test", metadata)).isEqualTo("target-test")
    }

    @Test
    fun `build throws when target not configured`() {
        val builder = OutboxRouteBuilder(selector)

        assertThatThrownBy { builder.build() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Target must be configured")
    }

    @Test
    fun `key with lambda sets key extractor`() {
        val rule =
            OutboxRouteBuilder(selector)
                .apply {
                    target("test")
                    key { payload, _ -> "key-$payload" }
                }.build()

        assertThat(rule.key("test", metadata)).isEqualTo("key-test")
    }

    @Test
    fun `key with BiFunction sets key extractor`() {
        val rule =
            OutboxRouteBuilder(selector)
                .apply {
                    target("test")
                    key(BiFunction { _, meta -> "key-${meta.key}" })
                }.build()

        assertThat(rule.key("payload", metadata)).isEqualTo("key-test-key")
    }

    @Test
    fun `key defaults to metadata key when not set`() {
        val rule =
            OutboxRouteBuilder(selector)
                .apply { target("test") }
                .build()

        assertThat(rule.key("payload", metadata)).isEqualTo("test-key")
    }

    @Test
    fun `key extractor can return null`() {
        val rule =
            OutboxRouteBuilder(selector)
                .apply {
                    target("test")
                    key { _, _ -> null }
                }.build()

        assertThat(rule.key("payload", metadata)).isNull()
    }

    @Test
    fun `header with static value adds single header`() {
        val rule =
            OutboxRouteBuilder(selector)
                .apply {
                    target("test")
                    header("key1", "value1")
                }.build()

        assertThat(rule.headers("payload", metadata))
            .containsEntry("key1", "value1")
    }

    @Test
    fun `multiple static headers are accumulated`() {
        val rule =
            OutboxRouteBuilder(selector)
                .apply {
                    target("test")
                    header("key1", "value1")
                    header("key2", "value2")
                }.build()

        assertThat(rule.headers("payload", metadata))
            .containsEntry("key1", "value1")
            .containsEntry("key2", "value2")
    }

    @Test
    fun `header with lambda adds dynamic header`() {
        val rule =
            OutboxRouteBuilder(selector)
                .apply {
                    target("test")
                    header("dynamic") { payload, _ -> "value-$payload" }
                }.build()

        assertThat(rule.headers("test", metadata))
            .containsEntry("dynamic", "value-test")
    }

    @Test
    fun `header with lambda can access metadata`() {
        val rule =
            OutboxRouteBuilder(selector)
                .apply {
                    target("test")
                    header("tenant") { _, meta -> meta.context["tenant"] ?: "unknown" }
                }.build()

        assertThat(rule.headers("payload", metadata))
            .containsEntry("tenant", "acme")
    }

    @Test
    fun `header with BiFunction adds dynamic header`() {
        val rule =
            OutboxRouteBuilder(selector)
                .apply {
                    target("test")
                    header("dynamic", BiFunction { payload, _ -> "value-$payload" })
                }.build()

        assertThat(rule.headers("test", metadata))
            .containsEntry("dynamic", "value-test")
    }

    @Test
    fun `header with BiFunction can access metadata`() {
        val rule =
            OutboxRouteBuilder(selector)
                .apply {
                    target("test")
                    header("key", BiFunction { _, meta -> meta.key })
                }.build()

        assertThat(rule.headers("payload", metadata))
            .containsEntry("key", "test-key")
    }

    @Test
    fun `headers with lambda sets headers provider`() {
        val rule =
            OutboxRouteBuilder(selector)
                .apply {
                    target("test")
                    headers { _, meta -> meta.context }
                }.build()

        assertThat(rule.headers("payload", metadata))
            .containsEntry("tenant", "acme")
    }

    @Test
    fun `headers with BiFunction sets headers provider`() {
        val rule =
            OutboxRouteBuilder(selector)
                .apply {
                    target("test")
                    headers(BiFunction { payload, _ -> mapOf("payload" to payload.toString()) })
                }.build()

        assertThat(rule.headers("test", metadata))
            .containsEntry("payload", "test")
    }

    @Test
    fun `headers defaults to empty map when not set`() {
        val rule =
            OutboxRouteBuilder(selector)
                .apply { target("test") }
                .build()

        assertThat(rule.headers("payload", metadata)).isEmpty()
    }

    @Test
    fun `header adds to existing headers from headers provider`() {
        val rule =
            OutboxRouteBuilder(selector)
                .apply {
                    target("test")
                    headers { _, _ -> mapOf("base" to "value") }
                    header("extra", "added")
                }.build()

        assertThat(rule.headers("payload", metadata))
            .containsEntry("base", "value")
            .containsEntry("extra", "added")
    }

    @Test
    fun `multiple mixed header calls accumulate correctly`() {
        val rule =
            OutboxRouteBuilder(selector)
                .apply {
                    target("test")
                    header("static", "value1")
                    header("lambda") { _, _ -> "value2" }
                    header("bifunction", BiFunction { _, _ -> "value3" })
                }.build()

        assertThat(rule.headers("payload", metadata))
            .containsEntry("static", "value1")
            .containsEntry("lambda", "value2")
            .containsEntry("bifunction", "value3")
            .hasSize(3)
    }

    @Test
    fun `build creates rule with correct selector`() {
        val customSelector = OutboxPayloadSelector.type(String::class.java)
        val rule =
            OutboxRouteBuilder(customSelector)
                .apply { target("test") }
                .build()

        assertThat(rule.selector.matches("string", metadata)).isTrue()
        assertThat(rule.selector.matches(123, metadata)).isFalse()
    }

    @Test
    fun `build creates fully configured rule`() {
        val rule =
            OutboxRouteBuilder(selector)
                .apply {
                    target { payload, _ -> "target-$payload" }
                    key { payload, _ -> "key-$payload" }
                    headers { _, meta -> meta.context }
                }.build()

        assertThat(rule.target("test", metadata)).isEqualTo("target-test")
        assertThat(rule.key("test", metadata)).isEqualTo("key-test")
        assertThat(rule.headers("test", metadata)).containsEntry("tenant", "acme")
    }

    @Test
    fun `mapping defaults to identity when not set`() {
        val rule =
            OutboxRouteBuilder(selector)
                .apply { target("test") }
                .build()

        val payload = "original-payload"
        assertThat(rule.mapping(payload, metadata)).isSameAs(payload)
    }

    @Test
    fun `mapping with lambda sets payload mapper`() {
        val rule =
            OutboxRouteBuilder(selector)
                .apply {
                    target("test")
                    mapping { payload, _ -> (payload as String).uppercase() }
                }.build()

        assertThat(rule.mapping("hello", metadata)).isEqualTo("HELLO")
    }

    @Test
    fun `mapping with BiFunction sets payload mapper`() {
        val rule =
            OutboxRouteBuilder(selector)
                .apply {
                    target("test")
                    mapping(BiFunction { payload, _ -> (payload as String).uppercase() })
                }.build()

        assertThat(rule.mapping("hello", metadata)).isEqualTo("HELLO")
    }

    @Test
    fun `mapping can access metadata`() {
        val rule =
            OutboxRouteBuilder(selector)
                .apply {
                    target("test")
                    mapping { payload, meta -> "$payload-${meta.key}" }
                }.build()

        assertThat(rule.mapping("value", metadata)).isEqualTo("value-test-key")
    }

    @Test
    fun `mapping can transform to different type`() {
        data class MappedPayload(
            val value: String,
            val key: String,
        )

        val rule =
            OutboxRouteBuilder(selector)
                .apply {
                    target("test")
                    mapping { payload, meta ->
                        MappedPayload(
                            value = payload as String,
                            key = meta.key,
                        )
                    }
                }.build()

        val mapped = rule.mapping("test-value", metadata) as MappedPayload
        assertThat(mapped.value).isEqualTo("test-value")
        assertThat(mapped.key).isEqualTo("test-key")
    }

    @Test
    fun `build creates fully configured rule with mapping`() {
        val rule =
            OutboxRouteBuilder(selector)
                .apply {
                    target { payload, _ -> "target-$payload" }
                    key { payload, _ -> "key-$payload" }
                    headers { _, meta -> meta.context }
                    mapping { payload, _ -> (payload as String).uppercase() }
                }.build()

        assertThat(rule.target("test", metadata)).isEqualTo("target-test")
        assertThat(rule.key("test", metadata)).isEqualTo("key-test")
        assertThat(rule.headers("test", metadata)).containsEntry("tenant", "acme")
        assertThat(rule.mapping("test", metadata)).isEqualTo("TEST")
    }

    @Test
    fun `filter defaults to true when not set`() {
        val rule =
            OutboxRouteBuilder(selector)
                .apply { target("test") }
                .build()

        assertThat(rule.filter("payload", metadata)).isTrue()
    }

    @Test
    fun `filter with lambda sets filter predicate`() {
        val rule =
            OutboxRouteBuilder(selector)
                .apply {
                    target("test")
                    filter { payload, _ -> (payload as String).startsWith("valid") }
                }.build()

        assertThat(rule.filter("valid-payload", metadata)).isTrue()
        assertThat(rule.filter("invalid-payload", metadata)).isFalse()
    }

    @Test
    fun `filter with BiPredicate sets filter predicate`() {
        val rule =
            OutboxRouteBuilder(selector)
                .apply {
                    target("test")
                    filter(BiPredicate { _, meta -> meta.context["tenant"] == "acme" })
                }.build()

        assertThat(rule.filter("payload", metadata)).isTrue()

        val otherMetadata =
            OutboxRecordMetadata(
                key = "test-key",
                handlerId = "test-handler",
                createdAt = Instant.now(),
                context = mapOf("tenant" to "other"),
            )
        assertThat(rule.filter("payload", otherMetadata)).isFalse()
    }

    @Test
    fun `filter can access both payload and metadata`() {
        val rule =
            OutboxRouteBuilder(selector)
                .apply {
                    target("test")
                    filter { payload, meta ->
                        (payload as String).isNotEmpty() && meta.key == "test-key"
                    }
                }.build()

        assertThat(rule.filter("valid", metadata)).isTrue()
        assertThat(rule.filter("", metadata)).isFalse()
    }

    @Test
    fun `build creates fully configured rule with filter`() {
        val rule =
            OutboxRouteBuilder(selector)
                .apply {
                    target { payload, _ -> "target-$payload" }
                    key { payload, _ -> "key-$payload" }
                    headers { _, meta -> meta.context }
                    mapping { payload, _ -> (payload as String).uppercase() }
                    filter { payload, _ -> (payload as String).isNotEmpty() }
                }.build()

        assertThat(rule.target("test", metadata)).isEqualTo("target-test")
        assertThat(rule.key("test", metadata)).isEqualTo("key-test")
        assertThat(rule.headers("test", metadata)).containsEntry("tenant", "acme")
        assertThat(rule.mapping("test", metadata)).isEqualTo("TEST")
        assertThat(rule.filter("test", metadata)).isTrue()
        assertThat(rule.filter("", metadata)).isFalse()
    }
}
