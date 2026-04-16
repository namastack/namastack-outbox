package io.namastack.outbox.sns

import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.routing.selector.OutboxPayloadSelector
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

class SnsOutboxRoutingTest {
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
        fun `snsOutboxRouting creates routing with routes`() {
            val routing =
                snsOutboxRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target("arn:aws:sns:us-east-1:123456789012:strings")
                    }
                }

            assertThat(
                routing.resolveTopicArn("test", metadata),
            ).isEqualTo("arn:aws:sns:us-east-1:123456789012:strings")
        }

        @Test
        fun `snsOutboxRouting creates routing with defaults`() {
            val routing =
                snsOutboxRouting {
                    defaults {
                        target("arn:aws:sns:us-east-1:123456789012:default-topic")
                    }
                }

            assertThat(routing.resolveTopicArn("any-payload", metadata))
                .isEqualTo("arn:aws:sns:us-east-1:123456789012:default-topic")
        }

        @Test
        fun `snsOutboxRouting supports all route options`() {
            val routing =
                snsOutboxRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target("arn:aws:sns:us-east-1:123456789012:strings")
                        key { payload, _ -> "key-$payload" }
                        headers { _, meta -> mapOf("tenant" to meta.context["tenant"]!!) }
                        mapping { payload, _ -> (payload as String).uppercase() }
                        filter { payload, _ -> (payload as String).isNotEmpty() }
                    }
                }

            assertThat(
                routing.resolveTopicArn("test", metadata),
            ).isEqualTo("arn:aws:sns:us-east-1:123456789012:strings")
            assertThat(routing.extractKey("test", metadata)).isEqualTo("key-test")
            assertThat(routing.buildHeaders("test", metadata)).containsEntry("tenant", "acme")
            assertThat(routing.mapPayload("test", metadata)).isEqualTo("TEST")
            assertThat(routing.shouldExternalize("test", metadata)).isTrue()
            assertThat(routing.shouldExternalize("", metadata)).isFalse()
        }

        @Test
        fun `snsOutboxRouting supports multiple routes`() {
            val routing =
                snsOutboxRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target("arn:aws:sns:us-east-1:123456789012:strings")
                    }
                    route(OutboxPayloadSelector.type(Int::class.javaObjectType)) {
                        target("arn:aws:sns:us-east-1:123456789012:ints")
                    }
                }

            assertThat(
                routing.resolveTopicArn("test", metadata),
            ).isEqualTo("arn:aws:sns:us-east-1:123456789012:strings")
            assertThat(routing.resolveTopicArn(123, metadata)).isEqualTo("arn:aws:sns:us-east-1:123456789012:ints")
        }
    }

    @Nested
    inner class JavaBuilder {
        @Test
        fun `builder creates routing with routes`() {
            val routing =
                SnsOutboxRouting
                    .builder()
                    .route(OutboxPayloadSelector.type(String::class.java)) { route ->
                        route.target("arn:aws:sns:us-east-1:123456789012:strings")
                    }.build()

            assertThat(
                routing.resolveTopicArn("test", metadata),
            ).isEqualTo("arn:aws:sns:us-east-1:123456789012:strings")
        }

        @Test
        fun `builder creates routing with defaults`() {
            val routing =
                SnsOutboxRouting
                    .builder()
                    .defaults { route ->
                        route.target("arn:aws:sns:us-east-1:123456789012:default-topic")
                    }.build()

            assertThat(routing.resolveTopicArn("any-payload", metadata))
                .isEqualTo("arn:aws:sns:us-east-1:123456789012:default-topic")
        }

        @Test
        fun `builder supports all route options`() {
            val routing =
                SnsOutboxRouting
                    .builder()
                    .route(OutboxPayloadSelector.type(String::class.java)) { route ->
                        route.target("arn:aws:sns:us-east-1:123456789012:strings")
                        route.key { payload, _ -> "key-$payload" }
                        route.headers { _, meta -> mapOf("tenant" to meta.context["tenant"]!!) }
                        route.mapping { payload, _ -> (payload as String).uppercase() }
                        route.filter { payload, _ -> (payload as String).isNotEmpty() }
                    }.build()

            assertThat(
                routing.resolveTopicArn("test", metadata),
            ).isEqualTo("arn:aws:sns:us-east-1:123456789012:strings")
            assertThat(routing.extractKey("test", metadata)).isEqualTo("key-test")
            assertThat(routing.buildHeaders("test", metadata)).containsEntry("tenant", "acme")
            assertThat(routing.mapPayload("test", metadata)).isEqualTo("TEST")
            assertThat(routing.shouldExternalize("test", metadata)).isTrue()
            assertThat(routing.shouldExternalize("", metadata)).isFalse()
        }

        @Test
        fun `builder supports method chaining`() {
            val builder = SnsOutboxRouting.builder()

            val result =
                builder
                    .route(
                        OutboxPayloadSelector.type(String::class.java),
                    ) { target("arn:aws:sns:us-east-1:123456789012:strings") }
                    .route(
                        OutboxPayloadSelector.type(Int::class.javaObjectType),
                    ) { target("arn:aws:sns:us-east-1:123456789012:ints") }
                    .defaults { target("arn:aws:sns:us-east-1:123456789012:default") }

            assertThat(result).isSameAs(builder)
        }

        @Test
        fun `builder supports multiple routes`() {
            val routing =
                SnsOutboxRouting
                    .builder()
                    .route(OutboxPayloadSelector.type(String::class.java)) {
                        target("arn:aws:sns:us-east-1:123456789012:strings")
                    }.route(OutboxPayloadSelector.type(Int::class.javaObjectType)) {
                        target("arn:aws:sns:us-east-1:123456789012:ints")
                    }.build()

            assertThat(
                routing.resolveTopicArn("test", metadata),
            ).isEqualTo("arn:aws:sns:us-east-1:123456789012:strings")
            assertThat(routing.resolveTopicArn(123, metadata)).isEqualTo("arn:aws:sns:us-east-1:123456789012:ints")
        }
    }

    @Nested
    inner class ResolveTopicArn {
        @Test
        fun `resolveTopicArn delegates to resolveTarget`() {
            val routing =
                snsOutboxRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target("arn:aws:sns:us-east-1:123456789012:my-topic")
                    }
                }

            assertThat(
                routing.resolveTopicArn("test", metadata),
            ).isEqualTo("arn:aws:sns:us-east-1:123456789012:my-topic")
            assertThat(routing.resolveTarget("test", metadata)).isEqualTo("arn:aws:sns:us-east-1:123456789012:my-topic")
        }

        @Test
        fun `resolveTopicArn throws when no matching route`() {
            val routing =
                snsOutboxRouting {
                    route(OutboxPayloadSelector.type(Int::class.javaObjectType)) {
                        target("arn:aws:sns:us-east-1:123456789012:ints")
                    }
                }

            assertThatThrownBy { routing.resolveTopicArn("string", metadata) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("No routing rule found")
        }

        @Test
        fun `resolveTopicArn supports dynamic topic ARN`() {
            val routing =
                snsOutboxRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target { payload, _ -> "arn:aws:sns:us-east-1:123456789012:topic-$payload" }
                    }
                }

            assertThat(routing.resolveTopicArn("orders", metadata))
                .isEqualTo("arn:aws:sns:us-east-1:123456789012:topic-orders")
        }
    }
}
