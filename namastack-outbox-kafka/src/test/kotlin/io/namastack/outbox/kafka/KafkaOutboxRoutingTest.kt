package io.namastack.outbox.kafka

import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.routing.selector.OutboxPayloadSelector
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.function.BiFunction
import java.util.function.BiPredicate

@DisplayName("KafkaOutboxRouting")
class KafkaOutboxRoutingTest {
    private val metadata =
        OutboxRecordMetadata(
            key = "order-123",
            handlerId = "test-handler",
            createdAt = Instant.now(),
            context = mapOf("tenant" to "acme"),
        )

    @Nested
    @DisplayName("resolveTopic()")
    inner class ResolveTopic {
        @Test
        fun `resolves static topic from defaults`() {
            val routing =
                kafkaRouting {
                    defaults { topic("default-topic") }
                }

            assertThat(routing.resolveTopic("payload", metadata)).isEqualTo("default-topic")
        }

        @Test
        fun `resolves topic from matching route`() {
            val routing =
                kafkaRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) {
                        topic("string-topic")
                    }
                    defaults { topic("default-topic") }
                }

            assertThat(routing.resolveTopic("payload", metadata)).isEqualTo("string-topic")
        }

        @Test
        fun `resolves dynamic topic`() {
            val routing =
                kafkaRouting {
                    defaults { topic { payload, _ -> "topic-$payload" } }
                }

            assertThat(routing.resolveTopic("orders", metadata)).isEqualTo("topic-orders")
        }

        @Test
        fun `throws when no matching route`() {
            val routing =
                kafkaRouting {
                    route(OutboxPayloadSelector.type(Int::class.javaObjectType)) {
                        topic("int-topic")
                    }
                }

            assertThatThrownBy { routing.resolveTopic("string", metadata) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("No routing rule found")
        }
    }

    @Nested
    @DisplayName("extractKey()")
    inner class ExtractKey {
        @Test
        fun `extracts key from metadata by default`() {
            val routing =
                kafkaRouting {
                    defaults { topic("events") }
                }

            assertThat(routing.extractKey("payload", metadata)).isEqualTo("order-123")
        }

        @Test
        fun `extracts custom key from payload`() {
            data class Order(
                val orderId: String,
            )

            val routing =
                kafkaRouting {
                    route(OutboxPayloadSelector.type(Order::class.java)) {
                        topic("orders")
                        key { payload, _ -> (payload as Order).orderId }
                    }
                }

            assertThat(routing.extractKey(Order("custom-key"), metadata)).isEqualTo("custom-key")
        }

        @Test
        fun `returns null when no matching route`() {
            val routing =
                kafkaRouting {
                    route(OutboxPayloadSelector.type(Int::class.javaObjectType)) {
                        topic("int-topic")
                    }
                }

            assertThat(routing.extractKey("string", metadata)).isNull()
        }
    }

    @Nested
    @DisplayName("buildHeaders()")
    inner class BuildHeaders {
        @Test
        fun `returns empty map by default`() {
            val routing =
                kafkaRouting {
                    defaults { topic("events") }
                }

            assertThat(routing.buildHeaders("payload", metadata)).isEmpty()
        }

        @Test
        fun `builds headers from context`() {
            val routing =
                kafkaRouting {
                    defaults {
                        topic("events")
                        headers { _, meta -> meta.context }
                    }
                }

            assertThat(routing.buildHeaders("payload", metadata))
                .containsEntry("tenant", "acme")
        }

        @Test
        fun `builds custom headers`() {
            val routing =
                kafkaRouting {
                    defaults {
                        topic("events")
                        headers { payload, _ -> mapOf("payload-type" to payload::class.simpleName!!) }
                    }
                }

            assertThat(routing.buildHeaders("payload", metadata))
                .containsEntry("payload-type", "String")
        }
    }

    @Nested
    @DisplayName("mapPayload()")
    inner class MapPayload {
        @Test
        fun `returns original payload by default`() {
            val routing =
                kafkaRouting {
                    defaults { topic("events") }
                }

            val payload = "original"
            assertThat(routing.mapPayload(payload, metadata)).isSameAs(payload)
        }

        @Test
        fun `maps payload using custom mapper`() {
            val routing =
                kafkaRouting {
                    defaults {
                        topic("events")
                        mapping { payload, _ -> (payload as String).uppercase() }
                    }
                }

            assertThat(routing.mapPayload("hello", metadata)).isEqualTo("HELLO")
        }

        @Test
        fun `maps to different type`() {
            data class InternalEvent(
                val id: String,
            )

            data class PublicEvent(
                val eventId: String,
            )

            val routing =
                kafkaRouting {
                    route(OutboxPayloadSelector.type(InternalEvent::class.java)) {
                        topic("events")
                        mapping { payload, _ -> PublicEvent((payload as InternalEvent).id) }
                    }
                }

            val result = routing.mapPayload(InternalEvent("123"), metadata)
            assertThat(result).isEqualTo(PublicEvent("123"))
        }

        @Test
        fun `returns original when no matching route`() {
            val routing =
                kafkaRouting {
                    route(OutboxPayloadSelector.type(Int::class.javaObjectType)) {
                        topic("int-topic")
                    }
                }

            val payload = "string"
            assertThat(routing.mapPayload(payload, metadata)).isSameAs(payload)
        }
    }

    @Nested
    @DisplayName("shouldExternalize()")
    inner class ShouldExternalize {
        @Test
        fun `returns true by default`() {
            val routing =
                kafkaRouting {
                    defaults { topic("events") }
                }

            assertThat(routing.shouldExternalize("payload", metadata)).isTrue()
        }

        @Test
        fun `returns true when filter passes`() {
            val routing =
                kafkaRouting {
                    defaults {
                        topic("events")
                        filter { payload, _ -> (payload as String).isNotEmpty() }
                    }
                }

            assertThat(routing.shouldExternalize("valid", metadata)).isTrue()
        }

        @Test
        fun `returns false when filter rejects`() {
            val routing =
                kafkaRouting {
                    defaults {
                        topic("events")
                        filter { payload, _ -> (payload as String).isNotEmpty() }
                    }
                }

            assertThat(routing.shouldExternalize("", metadata)).isFalse()
        }

        @Test
        fun `filters based on metadata`() {
            val routing =
                kafkaRouting {
                    defaults {
                        topic("events")
                        filter { _, meta -> meta.context["tenant"] == "acme" }
                    }
                }

            assertThat(routing.shouldExternalize("payload", metadata)).isTrue()

            val otherMetadata =
                OutboxRecordMetadata(
                    key = "key",
                    handlerId = "handler",
                    createdAt = Instant.now(),
                    context = mapOf("tenant" to "other"),
                )
            assertThat(routing.shouldExternalize("payload", otherMetadata)).isFalse()
        }

        @Test
        fun `returns true when no matching route`() {
            val routing =
                kafkaRouting {
                    route(OutboxPayloadSelector.type(Int::class.javaObjectType)) {
                        topic("int-topic")
                        filter { _, _ -> false }
                    }
                }

            assertThat(routing.shouldExternalize("string", metadata)).isTrue()
        }
    }

    @Nested
    @DisplayName("Builder (Java API)")
    inner class BuilderTest {
        @Test
        fun `builds routing with route`() {
            val routing =
                KafkaOutboxRouting
                    .builder()
                    .route(OutboxPayloadSelector.type(String::class.java)) { it.topic("strings") }
                    .build()

            assertThat(routing.resolveTopic("payload", metadata)).isEqualTo("strings")
        }

        @Test
        fun `builds routing with defaults`() {
            val routing =
                KafkaOutboxRouting
                    .builder()
                    .defaults { it.topic("default") }
                    .build()

            assertThat(routing.resolveTopic("any", metadata)).isEqualTo("default")
        }

        @Test
        fun `builds routing with multiple routes`() {
            val routing =
                KafkaOutboxRouting
                    .builder()
                    .route(OutboxPayloadSelector.type(String::class.java)) { it.topic("strings") }
                    .route(OutboxPayloadSelector.type(Int::class.javaObjectType)) { it.topic("ints") }
                    .defaults { it.topic("default") }
                    .build()

            assertThat(routing.resolveTopic("text", metadata)).isEqualTo("strings")
            assertThat(routing.resolveTopic(123, metadata)).isEqualTo("ints")
            assertThat(routing.resolveTopic(listOf(1), metadata)).isEqualTo("default")
        }

        @Test
        fun `builds routing with all options using Java API`() {
            data class Event(
                val id: String,
                val status: String,
            )

            val routing =
                KafkaOutboxRouting
                    .builder()
                    .route(OutboxPayloadSelector.type(Event::class.java)) { builder ->
                        builder.topic("events")
                        builder.key(BiFunction { payload, _ -> (payload as Event).id })
                        builder.headers(BiFunction { _, meta -> meta.context })
                        builder.mapping(BiFunction { payload, _ -> mapOf("id" to (payload as Event).id) })
                        builder.filter(BiPredicate { payload, _ -> (payload as Event).status != "DELETED" })
                    }.build()

            val event = Event("evt-1", "ACTIVE")
            assertThat(routing.resolveTopic(event, metadata)).isEqualTo("events")
            assertThat(routing.extractKey(event, metadata)).isEqualTo("evt-1")
            assertThat(routing.buildHeaders(event, metadata)).containsEntry("tenant", "acme")
            assertThat(routing.mapPayload(event, metadata)).isEqualTo(mapOf("id" to "evt-1"))
            assertThat(routing.shouldExternalize(event, metadata)).isTrue()
            assertThat(routing.shouldExternalize(Event("evt-2", "DELETED"), metadata)).isFalse()
        }
    }

    @Nested
    @DisplayName("topic() extension functions")
    inner class TopicExtensions {
        @Test
        fun `topic with string sets static topic`() {
            val routing =
                kafkaRouting {
                    defaults { topic("my-topic") }
                }

            assertThat(routing.resolveTopic("payload", metadata)).isEqualTo("my-topic")
        }

        @Test
        fun `topic with lambda sets dynamic topic`() {
            val routing =
                kafkaRouting {
                    defaults { topic { payload, _ -> "topic-$payload" } }
                }

            assertThat(routing.resolveTopic("test", metadata)).isEqualTo("topic-test")
        }

        @Test
        fun `topic with BiFunction sets dynamic topic`() {
            val routing =
                kafkaRouting {
                    defaults { topic(BiFunction { _, meta -> "topic-${meta.key}" }) }
                }

            assertThat(routing.resolveTopic("payload", metadata)).isEqualTo("topic-order-123")
        }
    }

    @Nested
    @DisplayName("routing precedence")
    inner class RoutingPrecedence {
        @Test
        fun `first matching route wins`() {
            val routing =
                kafkaRouting {
                    route(OutboxPayloadSelector.predicate { _, _ -> true }) { topic("first") }
                    route(OutboxPayloadSelector.predicate { _, _ -> true }) { topic("second") }
                }

            assertThat(routing.resolveTopic("payload", metadata)).isEqualTo("first")
        }

        @Test
        fun `specific route takes precedence over defaults`() {
            val routing =
                kafkaRouting {
                    route(OutboxPayloadSelector.type(String::class.java)) { topic("strings") }
                    defaults { topic("default") }
                }

            assertThat(routing.resolveTopic("text", metadata)).isEqualTo("strings")
        }

        @Test
        fun `defaults used when no route matches`() {
            val routing =
                kafkaRouting {
                    route(OutboxPayloadSelector.type(Int::class.javaObjectType)) { topic("ints") }
                    defaults { topic("default") }
                }

            assertThat(routing.resolveTopic("text", metadata)).isEqualTo("default")
        }
    }
}
