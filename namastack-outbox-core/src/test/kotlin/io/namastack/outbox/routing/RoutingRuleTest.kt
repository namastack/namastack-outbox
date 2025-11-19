package io.namastack.outbox.routing

import io.namastack.outbox.OutboxRecordTestFactory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("RoutingRule Tests")
class RoutingRuleTest {
    private val testRecord = OutboxRecordTestFactory.outboxRecord()

    @Nested
    @DisplayName("RoutingRule.Builder Creation")
    inner class BuilderCreation {
        @Test
        fun `should create builder with defaults`() {
            val builder = RoutingRule.Builder()

            assertThat(builder).isNotNull
        }
    }

    @Nested
    @DisplayName("RoutingRule.Builder.mapper()")
    inner class MapperFunction {
        @Test
        fun `should set custom mapper function`() {
            val rule =
                RoutingRule
                    .Builder()
                    .mapper { it.payload.uppercase() }
                    .target("orders")
                    .build()

            val result = rule.mapper(testRecord)
            assertThat(result).isEqualTo(testRecord.payload.uppercase())
        }

        @Test
        fun `should use payload as default mapper`() {
            val rule =
                RoutingRule
                    .Builder()
                    .target("orders")
                    .build()

            val result = rule.mapper(testRecord)
            assertThat(result).isEqualTo(testRecord.payload)
        }

        @Test
        fun `should allow method chaining`() {
            val builder = RoutingRule.Builder()
            val result = builder.mapper { it.payload }

            assertThat(result).isNotNull
        }
    }

    @Nested
    @DisplayName("RoutingRule.Builder.target()")
    inner class TargetFunction {
        @Test
        fun `should set target with function`() {
            val rule =
                RoutingRule
                    .Builder()
                    .target { RoutingTarget("orders") }
                    .build()

            val result = rule.target(testRecord)
            assertThat(result.target).isEqualTo("orders")
        }

        @Test
        fun `should set target with fixed string and key`() {
            val rule =
                RoutingRule
                    .Builder()
                    .target("orders", "orderId")
                    .build()

            val result = rule.target(testRecord)
            assertThat(result.target).isEqualTo("orders")
            assertThat(result.key).isEqualTo("orderId")
        }

        @Test
        fun `should throw IllegalStateException when target not set`() {
            assertThatThrownBy {
                RoutingRule.Builder().build()
            }.isInstanceOf(IllegalStateException::class.java)
        }

        @Test
        fun `should allow method chaining`() {
            val builder = RoutingRule.Builder()
            val result = builder.target("orders")

            assertThat(result).isNotNull
        }
    }

    @Nested
    @DisplayName("RoutingRule.Builder.headers()")
    inner class HeadersFunction {
        @Test
        fun `should set headers from map`() {
            val rule =
                RoutingRule
                    .Builder()
                    .target("orders")
                    .headers { mapOf("version" to "1.0") }
                    .build()

            val result = rule.headers(testRecord)
            assertThat(result).isEqualTo(mapOf("version" to "1.0"))
        }

        @Test
        fun `should merge multiple headers calls`() {
            val rule =
                RoutingRule
                    .Builder()
                    .target("orders")
                    .headers { mapOf("version" to "1.0") }
                    .headers { mapOf("source" to "order-service") }
                    .build()

            val result = rule.headers(testRecord)
            assertThat(result).isEqualTo(mapOf("version" to "1.0", "source" to "order-service"))
        }

        @Test
        fun `should override headers with same key`() {
            val rule =
                RoutingRule
                    .Builder()
                    .target("orders")
                    .headers { mapOf("version" to "1.0") }
                    .headers { mapOf("version" to "2.0") }
                    .build()

            val result = rule.headers(testRecord)
            assertThat(result).isEqualTo(mapOf("version" to "2.0"))
        }

        @Test
        fun `should return empty map by default`() {
            val rule =
                RoutingRule
                    .Builder()
                    .target("orders")
                    .build()

            val result = rule.headers(testRecord)
            assertThat(result).isEmpty()
        }

        @Test
        fun `should allow method chaining`() {
            val builder = RoutingRule.Builder()
            val result = builder.headers { mapOf("v" to "1.0") }

            assertThat(result).isNotNull
        }
    }

    @Nested
    @DisplayName("RoutingRule.Builder.header()")
    inner class HeaderFunction {
        @Test
        fun `should add single header`() {
            val rule =
                RoutingRule
                    .Builder()
                    .target("orders")
                    .header("version", "1.0")
                    .build()

            val result = rule.headers(testRecord)
            assertThat(result).isEqualTo(mapOf("version" to "1.0"))
        }

        @Test
        fun `should add multiple headers progressively`() {
            val rule =
                RoutingRule
                    .Builder()
                    .target("orders")
                    .header("version", "1.0")
                    .header("source", "order-service")
                    .header("timestamp", "2025-11-19")
                    .build()

            val result = rule.headers(testRecord)
            assertThat(result).isEqualTo(
                mapOf(
                    "version" to "1.0",
                    "source" to "order-service",
                    "timestamp" to "2025-11-19",
                ),
            )
        }

        @Test
        fun `should merge with headers() calls`() {
            val rule =
                RoutingRule
                    .Builder()
                    .target("orders")
                    .headers { mapOf("v1" to "1.0") }
                    .header("v2", "2.0")
                    .headers { mapOf("v3" to "3.0") }
                    .build()

            val result = rule.headers(testRecord)
            assertThat(result).isEqualTo(
                mapOf("v1" to "1.0", "v2" to "2.0", "v3" to "3.0"),
            )
        }

        @Test
        fun `should override header with same key`() {
            val rule =
                RoutingRule
                    .Builder()
                    .target("orders")
                    .header("version", "1.0")
                    .header("version", "2.0")
                    .build()

            val result = rule.headers(testRecord)
            assertThat(result).isEqualTo(mapOf("version" to "2.0"))
        }

        @Test
        fun `should allow method chaining`() {
            val builder = RoutingRule.Builder()
            val result = builder.header("key", "value")

            assertThat(result).isNotNull
        }
    }

    @Nested
    @DisplayName("RoutingRule.Builder.build()")
    inner class BuildFunction {
        @Test
        fun `should build complete RoutingRule`() {
            val rule =
                RoutingRule
                    .Builder()
                    .target("orders")
                    .mapper { it.payload }
                    .headers { mapOf("v" to "1.0") }
                    .build()

            assertThat(rule).isNotNull
            assertThat(rule.mapper).isNotNull
            assertThat(rule.headers).isNotNull
            assertThat(rule.target).isNotNull
        }

        @Test
        fun `should throw when target not configured`() {
            assertThatThrownBy {
                RoutingRule
                    .Builder()
                    .mapper { it.payload }
                    .build()
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("target")
        }
    }

    @Nested
    @DisplayName("RoutingRule Fluent API")
    inner class FluentAPI {
        @Test
        fun `should support complete fluent chain`() {
            val rule =
                RoutingRule
                    .Builder()
                    .mapper { it.payload.uppercase() }
                    .target("orders", "orderId")
                    .header("version", "1.0")
                    .headers { mapOf("source" to "order-service") }
                    .build()

            val result = rule.target(testRecord)
            assertThat(result.target).isEqualTo("orders")
            assertThat(result.key).isEqualTo("orderId")

            val headers = rule.headers(testRecord)
            assertThat(headers).isEqualTo(mapOf("version" to "1.0", "source" to "order-service"))
        }
    }
}
