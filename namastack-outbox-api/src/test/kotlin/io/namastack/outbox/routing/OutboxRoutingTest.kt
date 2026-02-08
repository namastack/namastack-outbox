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
        configurer: OutboxRoutingConfigurer,
    ) : OutboxRouting(configurer)

    private fun testRouting(configurer: OutboxRoutingConfigurer.() -> Unit): TestOutboxRouting {
        val builder = OutboxRoutingConfigurer()
        builder.configurer()
        return TestOutboxRouting(builder)
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
    }
}
