package io.namastack.outbox.rabbit

import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.routing.selector.OutboxPayloadSelector
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

class RabbitOutboxRoutingTest {
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
        fun `rabbitOutboxRouting creates routing with routes`() {
            val routing =
                rabbitOutboxRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target("strings-exchange")
                    }
                }

            assertThat(routing.resolveExchange("test", metadata)).isEqualTo("strings-exchange")
        }

        @Test
        fun `rabbitOutboxRouting creates routing with defaults`() {
            val routing =
                rabbitOutboxRouting {
                    defaults {
                        target("default-exchange")
                    }
                }

            assertThat(routing.resolveExchange("any-payload", metadata)).isEqualTo("default-exchange")
        }

        @Test
        fun `rabbitOutboxRouting supports all route options`() {
            val routing =
                rabbitOutboxRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target("strings")
                        key { payload, _ -> "key-$payload" }
                        headers { _, meta -> mapOf("tenant" to meta.context["tenant"]!!) }
                        mapping { payload, _ -> (payload as String).uppercase() }
                        filter { payload, _ -> (payload as String).isNotEmpty() }
                    }
                }

            assertThat(routing.resolveExchange("test", metadata)).isEqualTo("strings")
            assertThat(routing.extractKey("test", metadata)).isEqualTo("key-test")
            assertThat(routing.buildHeaders("test", metadata)).containsEntry("tenant", "acme")
            assertThat(routing.mapPayload("test", metadata)).isEqualTo("TEST")
            assertThat(routing.shouldExternalize("test", metadata)).isTrue()
            assertThat(routing.shouldExternalize("", metadata)).isFalse()
        }

        @Test
        fun `rabbitOutboxRouting supports multiple routes`() {
            val routing =
                rabbitOutboxRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target("strings")
                    }
                    route(OutboxPayloadSelector.type(Int::class.javaObjectType)) {
                        target("ints")
                    }
                }

            assertThat(routing.resolveExchange("test", metadata)).isEqualTo("strings")
            assertThat(routing.resolveExchange(123, metadata)).isEqualTo("ints")
        }
    }

    @Nested
    inner class JavaBuilder {
        @Test
        fun `builder creates routing with routes`() {
            val routing =
                RabbitOutboxRouting
                    .builder()
                    .route(OutboxPayloadSelector.type(String::class.java)) { route ->
                        route.target("strings")
                    }.build()

            assertThat(routing.resolveExchange("test", metadata)).isEqualTo("strings")
        }

        @Test
        fun `builder creates routing with defaults`() {
            val routing =
                RabbitOutboxRouting
                    .builder()
                    .defaults { route ->
                        route.target("default-exchange")
                    }.build()

            assertThat(routing.resolveExchange("any-payload", metadata)).isEqualTo("default-exchange")
        }

        @Test
        fun `builder supports all route options`() {
            val routing =
                RabbitOutboxRouting
                    .builder()
                    .route(OutboxPayloadSelector.type(String::class.java)) { route ->
                        route.target("strings")
                        route.key { payload, _ -> "key-$payload" }
                        route.headers { _, meta -> mapOf("tenant" to meta.context["tenant"]!!) }
                        route.mapping { payload, _ -> (payload as String).uppercase() }
                        route.filter { payload, _ -> (payload as String).isNotEmpty() }
                    }.build()

            assertThat(routing.resolveExchange("test", metadata)).isEqualTo("strings")
            assertThat(routing.extractKey("test", metadata)).isEqualTo("key-test")
            assertThat(routing.buildHeaders("test", metadata)).containsEntry("tenant", "acme")
            assertThat(routing.mapPayload("test", metadata)).isEqualTo("TEST")
            assertThat(routing.shouldExternalize("test", metadata)).isTrue()
            assertThat(routing.shouldExternalize("", metadata)).isFalse()
        }

        @Test
        fun `builder supports method chaining`() {
            val builder = RabbitOutboxRouting.builder()

            val result =
                builder
                    .route(OutboxPayloadSelector.type(String::class.java)) { target("strings") }
                    .route(OutboxPayloadSelector.type(Int::class.javaObjectType)) { target("ints") }
                    .defaults { target("default") }

            assertThat(result).isSameAs(builder)
        }

        @Test
        fun `builder supports multiple routes`() {
            val routing =
                RabbitOutboxRouting
                    .builder()
                    .route(OutboxPayloadSelector.type(String::class.java)) { target("strings") }
                    .route(OutboxPayloadSelector.type(Int::class.javaObjectType)) { target("ints") }
                    .build()

            assertThat(routing.resolveExchange("test", metadata)).isEqualTo("strings")
            assertThat(routing.resolveExchange(123, metadata)).isEqualTo("ints")
        }
    }

    @Nested
    inner class ResolveExchange {
        @Test
        fun `resolveExchange delegates to resolveTarget`() {
            val routing =
                rabbitOutboxRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target("my-exchange")
                    }
                }

            assertThat(routing.resolveExchange("test", metadata)).isEqualTo("my-exchange")
            assertThat(routing.resolveTarget("test", metadata)).isEqualTo("my-exchange")
        }

        @Test
        fun `resolveExchange throws when no matching route`() {
            val routing =
                rabbitOutboxRouting {
                    route(OutboxPayloadSelector.type(Int::class.javaObjectType)) {
                        target("ints")
                    }
                }

            assertThatThrownBy { routing.resolveExchange("string", metadata) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("No routing rule found")
        }

        @Test
        fun `resolveExchange supports dynamic exchange`() {
            val routing =
                rabbitOutboxRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target { payload, _ -> "exchange-$payload" }
                    }
                }

            assertThat(routing.resolveExchange("orders", metadata)).isEqualTo("exchange-orders")
        }
    }

    @Nested
    inner class RoutePrecedence {
        @Test
        fun `first matching route wins`() {
            val routing =
                rabbitOutboxRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target("first")
                    }
                    route(OutboxPayloadSelector.predicate { _, _ -> true }) {
                        target("second")
                    }
                }

            assertThat(routing.resolveExchange("test", metadata)).isEqualTo("first")
        }

        @Test
        fun `defaults used when no route matches`() {
            val routing =
                rabbitOutboxRouting {
                    route(OutboxPayloadSelector.type(Int::class.javaObjectType)) {
                        target("ints")
                    }
                    defaults {
                        target("default-exchange")
                    }
                }

            assertThat(routing.resolveExchange("string", metadata)).isEqualTo("default-exchange")
        }

        @Test
        fun `specific route takes precedence over defaults`() {
            val routing =
                rabbitOutboxRouting {
                    defaults {
                        target("default-exchange")
                    }
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        target("strings")
                    }
                }

            assertThat(routing.resolveExchange("test", metadata)).isEqualTo("strings")
        }
    }
}
