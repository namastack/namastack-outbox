package io.namastack.outbox.routing

import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.routing.selector.OutboxPayloadSelector
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

class OutboxRoutingTest {
    private val metadata =
        OutboxRecordMetadata(
            key = "test-key",
            handlerId = "test-handler",
            createdAt = Instant.now(),
            context = mapOf("tenant" to "acme"),
        )

    private class TestOutboxRouting(
        rules: List<OutboxRoute>,
        defaultRule: OutboxRoute?,
    ) : OutboxRouting(rules, defaultRule)

    private fun testRouting(configurer: OutboxRoutingConfigurer.() -> Unit): TestOutboxRouting {
        val builder = OutboxRoutingConfigurer()
        builder.configurer()
        return TestOutboxRouting(builder.rules(), builder.defaultRule())
    }

    @Nested
    inner class FindRule {
        @Test
        fun `returns matching rule`() {
            val routing =
                testRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target("strings")
                    }
                }

            val rule = routing.findRule("test", metadata)
            assertThat(rule).isNotNull
            assertThat(rule!!.target("test", metadata)).isEqualTo("strings")
        }

        @Test
        fun `returns default rule when no match`() {
            val routing =
                testRouting {
                    route(OutboxPayloadSelector.type(Int::class.javaObjectType)) {
                        target("ints")
                    }
                    defaults {
                        target("default")
                    }
                }

            val rule = routing.findRule("string", metadata)
            assertThat(rule).isNotNull
            assertThat(rule!!.target("string", metadata)).isEqualTo("default")
        }

        @Test
        fun `returns null when no match and no defaults`() {
            val routing =
                testRouting {
                    route(OutboxPayloadSelector.type(Int::class.javaObjectType)) {
                        target("ints")
                    }
                }

            assertThat(routing.findRule("string", metadata)).isNull()
        }

        @Test
        fun `returns null for empty routing`() {
            val routing = TestOutboxRouting(emptyList(), null)

            assertThat(routing.findRule("test", metadata)).isNull()
        }

        @Test
        fun `returns default rule for empty rules list with defaults`() {
            val builder = OutboxRoute.Builder(OutboxPayloadSelector.all())
            builder.target("default")
            val defaultRoute = builder.build()

            val routing = TestOutboxRouting(emptyList(), defaultRoute)

            val rule = routing.findRule("test", metadata)
            assertThat(rule).isNotNull
            assertThat(rule!!.target("test", metadata)).isEqualTo("default")
        }
    }

    @Nested
    inner class ResolveTarget {
        @Test
        fun `resolves target from matching route`() {
            val routing =
                testRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target("strings")
                    }
                }

            assertThat(routing.resolveTarget("test", metadata)).isEqualTo("strings")
        }

        @Test
        fun `resolves target from defaults when no match`() {
            val routing =
                testRouting {
                    route(OutboxPayloadSelector.type(Int::class.javaObjectType)) {
                        target("ints")
                    }
                    defaults {
                        target("default-target")
                    }
                }

            assertThat(routing.resolveTarget("string-payload", metadata)).isEqualTo("default-target")
        }

        @Test
        fun `throws when no matching route and no defaults`() {
            val routing =
                testRouting {
                    route(OutboxPayloadSelector.type(Int::class.javaObjectType)) {
                        target("ints")
                    }
                }

            assertThatThrownBy { routing.resolveTarget("string-payload", metadata) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("No routing rule found")
        }

        @Test
        fun `resolves dynamic target based on payload`() {
            val routing =
                testRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target { payload, _ -> "target-$payload" }
                    }
                }

            assertThat(routing.resolveTarget("test", metadata)).isEqualTo("target-test")
        }

        @Test
        fun `resolves dynamic target based on metadata`() {
            val routing =
                testRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target { _, meta -> "target-${meta.handlerId}" }
                    }
                }

            assertThat(routing.resolveTarget("test", metadata)).isEqualTo("target-test-handler")
        }

        @Test
        fun `error message contains payload class name`() {
            val routing =
                testRouting {
                    route(OutboxPayloadSelector.type(Int::class.javaObjectType)) {
                        target("ints")
                    }
                }

            assertThatThrownBy { routing.resolveTarget("string-payload", metadata) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("java.lang.String")
        }
    }

    @Nested
    inner class ExtractKey {
        @Test
        fun `extracts key from matching route`() {
            val routing =
                testRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target("strings")
                        key { payload, _ -> "key-$payload" }
                    }
                }

            assertThat(routing.extractKey("test", metadata)).isEqualTo("key-test")
        }

        @Test
        fun `returns metadata key by default`() {
            val routing =
                testRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target("strings")
                    }
                }

            assertThat(routing.extractKey("test", metadata)).isEqualTo("test-key")
        }

        @Test
        fun `returns null when no matching route`() {
            val routing =
                testRouting {
                    route(OutboxPayloadSelector.type(Int::class.javaObjectType)) {
                        target("ints")
                    }
                }

            assertThat(routing.extractKey("string-payload", metadata)).isNull()
        }

        @Test
        fun `extracts key using metadata`() {
            val routing =
                testRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target("strings")
                        key { _, meta -> "key-${meta.handlerId}" }
                    }
                }

            assertThat(routing.extractKey("test", metadata)).isEqualTo("key-test-handler")
        }

        @Test
        fun `extracts key from defaults when no specific route matches`() {
            val routing =
                testRouting {
                    route(OutboxPayloadSelector.type(Int::class.javaObjectType)) {
                        target("ints")
                        key { _, _ -> "int-key" }
                    }
                    defaults {
                        target("default")
                        key { payload, _ -> "default-$payload" }
                    }
                }

            assertThat(routing.extractKey("string", metadata)).isEqualTo("default-string")
        }
    }

    @Nested
    inner class BuildHeaders {
        @Test
        fun `builds headers from matching route`() {
            val routing =
                testRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target("strings")
                        headers { _, meta -> mapOf("tenant" to meta.context["tenant"]!!) }
                    }
                }

            assertThat(routing.buildHeaders("test", metadata))
                .containsEntry("tenant", "acme")
        }

        @Test
        fun `returns empty map by default`() {
            val routing =
                testRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target("strings")
                    }
                }

            assertThat(routing.buildHeaders("test", metadata)).isEmpty()
        }

        @Test
        fun `returns empty map when no matching route`() {
            val routing =
                testRouting {
                    route(OutboxPayloadSelector.type(Int::class.javaObjectType)) {
                        target("ints")
                    }
                }

            assertThat(routing.buildHeaders("string-payload", metadata)).isEmpty()
        }

        @Test
        fun `builds multiple headers`() {
            val routing =
                testRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target("strings")
                        headers { payload, meta ->
                            mapOf(
                                "tenant" to meta.context["tenant"]!!,
                                "handler" to meta.handlerId,
                                "payload-length" to (payload as String).length.toString(),
                            )
                        }
                    }
                }

            val headers = routing.buildHeaders("test", metadata)
            assertThat(headers)
                .hasSize(3)
                .containsEntry("tenant", "acme")
                .containsEntry("handler", "test-handler")
                .containsEntry("payload-length", "4")
        }

        @Test
        fun `builds headers from defaults when no specific route matches`() {
            val routing =
                testRouting {
                    route(OutboxPayloadSelector.type(Int::class.javaObjectType)) {
                        target("ints")
                    }
                    defaults {
                        target("default")
                        headers { _, meta -> mapOf("default-header" to meta.key) }
                    }
                }

            assertThat(routing.buildHeaders("string", metadata))
                .containsEntry("default-header", "test-key")
        }
    }

    @Nested
    inner class MapPayload {
        @Test
        fun `maps payload from matching route`() {
            val routing =
                testRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target("strings")
                        mapping { payload, _ -> (payload as String).uppercase() }
                    }
                }

            assertThat(routing.mapPayload("test", metadata)).isEqualTo("TEST")
        }

        @Test
        fun `returns original payload by default`() {
            val routing =
                testRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target("strings")
                    }
                }

            val payload = "test"
            assertThat(routing.mapPayload(payload, metadata)).isSameAs(payload)
        }

        @Test
        fun `returns original payload when no matching route`() {
            val routing =
                testRouting {
                    route(OutboxPayloadSelector.type(Int::class.javaObjectType)) {
                        target("ints")
                    }
                }

            val payload = "string-payload"
            assertThat(routing.mapPayload(payload, metadata)).isSameAs(payload)
        }

        @Test
        fun `can transform to different type`() {
            data class MappedEvent(
                val value: String,
            )

            val routing =
                testRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target("strings")
                        mapping { payload, _ -> MappedEvent(payload as String) }
                    }
                }

            val result = routing.mapPayload("test", metadata) as MappedEvent
            assertThat(result.value).isEqualTo("test")
        }

        @Test
        fun `maps payload using metadata`() {
            val routing =
                testRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target("strings")
                        mapping { payload, meta -> "$payload-${meta.handlerId}" }
                    }
                }

            assertThat(routing.mapPayload("test", metadata)).isEqualTo("test-test-handler")
        }

        @Test
        fun `maps payload from defaults when no specific route matches`() {
            val routing =
                testRouting {
                    route(OutboxPayloadSelector.type(Int::class.javaObjectType)) {
                        target("ints")
                    }
                    defaults {
                        target("default")
                        mapping { payload, _ -> "mapped-$payload" }
                    }
                }

            assertThat(routing.mapPayload("string", metadata)).isEqualTo("mapped-string")
        }
    }

    @Nested
    inner class ShouldExternalize {
        @Test
        fun `returns true when filter passes`() {
            val routing =
                testRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target("strings")
                        filter { payload, _ -> (payload as String).startsWith("valid") }
                    }
                }

            assertThat(routing.shouldExternalize("valid-payload", metadata)).isTrue()
        }

        @Test
        fun `returns false when filter rejects`() {
            val routing =
                testRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target("strings")
                        filter { payload, _ -> (payload as String).startsWith("valid") }
                    }
                }

            assertThat(routing.shouldExternalize("invalid-payload", metadata)).isFalse()
        }

        @Test
        fun `returns true by default`() {
            val routing =
                testRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target("strings")
                    }
                }

            assertThat(routing.shouldExternalize("test", metadata)).isTrue()
        }

        @Test
        fun `returns true when no matching route`() {
            val routing =
                testRouting {
                    route(OutboxPayloadSelector.type(Int::class.javaObjectType)) {
                        target("ints")
                    }
                }

            assertThat(routing.shouldExternalize("string-payload", metadata)).isTrue()
        }

        @Test
        fun `filter can access metadata`() {
            val routing =
                testRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target("strings")
                        filter { _, meta -> meta.context["tenant"] == "acme" }
                    }
                }

            assertThat(routing.shouldExternalize("test", metadata)).isTrue()

            val otherMetadata =
                OutboxRecordMetadata(
                    key = "test-key",
                    handlerId = "test-handler",
                    createdAt = Instant.now(),
                    context = mapOf("tenant" to "other"),
                )
            assertThat(routing.shouldExternalize("test", otherMetadata)).isFalse()
        }

        @Test
        fun `filter from defaults when no specific route matches`() {
            val routing =
                testRouting {
                    route(OutboxPayloadSelector.type(Int::class.javaObjectType)) {
                        target("ints")
                    }
                    defaults {
                        target("default")
                        filter { payload, _ -> (payload as String).length > 3 }
                    }
                }

            assertThat(routing.shouldExternalize("long-string", metadata)).isTrue()
            assertThat(routing.shouldExternalize("ab", metadata)).isFalse()
        }
    }

    @Nested
    inner class RoutePrecedence {
        @Test
        fun `first matching route wins`() {
            val routing =
                testRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target("first")
                    }
                    route(OutboxPayloadSelector.predicate { _, _ -> true }) {
                        target("second")
                    }
                }

            assertThat(routing.resolveTarget("test", metadata)).isEqualTo("first")
        }

        @Test
        fun `defaults used when no route matches`() {
            val routing =
                testRouting {
                    route(OutboxPayloadSelector.type(Int::class.javaObjectType)) {
                        target("ints")
                    }
                    defaults {
                        target("default")
                    }
                }

            assertThat(routing.resolveTarget("string", metadata)).isEqualTo("default")
        }

        @Test
        fun `specific route takes precedence over defaults`() {
            val routing =
                testRouting {
                    defaults {
                        target("default")
                    }
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target("strings")
                    }
                }

            assertThat(routing.resolveTarget("test", metadata)).isEqualTo("strings")
        }

        @Test
        fun `order of route declarations matters`() {
            val routing =
                testRouting {
                    route(OutboxPayloadSelector.predicate { payload, _ -> (payload as String).startsWith("a") }) {
                        target("starts-with-a")
                    }
                    route(OutboxPayloadSelector.predicate { payload, _ -> (payload as String).length > 3 }) {
                        target("longer-than-3")
                    }
                }

            // "apple" matches both, but first route wins
            assertThat(routing.resolveTarget("apple", metadata)).isEqualTo("starts-with-a")
            // "banana" only matches second route
            assertThat(routing.resolveTarget("test", metadata)).isEqualTo("longer-than-3")
        }

        @Test
        fun `routes are evaluated in order until match`() {
            val routing =
                testRouting {
                    route(OutboxPayloadSelector.type(Int::class.javaObjectType)) {
                        target("ints")
                    }
                    route(OutboxPayloadSelector.type(Long::class.javaObjectType)) {
                        target("longs")
                    }
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target("strings")
                    }
                }

            assertThat(routing.resolveTarget(123, metadata)).isEqualTo("ints")
            assertThat(routing.resolveTarget(123L, metadata)).isEqualTo("longs")
            assertThat(routing.resolveTarget("test", metadata)).isEqualTo("strings")
        }
    }

    @Nested
    inner class EdgeCases {
        @Test
        fun `empty routing returns null from findRule`() {
            val routing = TestOutboxRouting(emptyList(), null)

            assertThat(routing.findRule("test", metadata)).isNull()
        }

        @Test
        fun `empty routing throws on resolveTarget`() {
            val routing = TestOutboxRouting(emptyList(), null)

            assertThatThrownBy { routing.resolveTarget("test", metadata) }
                .isInstanceOf(IllegalStateException::class.java)
        }

        @Test
        fun `empty routing returns null from extractKey`() {
            val routing = TestOutboxRouting(emptyList(), null)

            assertThat(routing.extractKey("test", metadata)).isNull()
        }

        @Test
        fun `empty routing returns empty map from buildHeaders`() {
            val routing = TestOutboxRouting(emptyList(), null)

            assertThat(routing.buildHeaders("test", metadata)).isEmpty()
        }

        @Test
        fun `empty routing returns original payload from mapPayload`() {
            val routing = TestOutboxRouting(emptyList(), null)
            val payload = "test"

            assertThat(routing.mapPayload(payload, metadata)).isSameAs(payload)
        }

        @Test
        fun `empty routing returns true from shouldExternalize`() {
            val routing = TestOutboxRouting(emptyList(), null)

            assertThat(routing.shouldExternalize("test", metadata)).isTrue()
        }

        @Test
        fun `only defaults configured works correctly`() {
            val routing =
                testRouting {
                    defaults {
                        target("default-target")
                        key { _, meta -> meta.key }
                        headers { _, _ -> mapOf("source" to "default") }
                    }
                }

            assertThat(routing.resolveTarget("anything", metadata)).isEqualTo("default-target")
            assertThat(routing.extractKey("anything", metadata)).isEqualTo("test-key")
            assertThat(routing.buildHeaders("anything", metadata)).containsEntry("source", "default")
        }
    }
}
