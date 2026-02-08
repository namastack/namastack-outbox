package io.namastack.outbox.routing

import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.routing.selector.OutboxPayloadSelector
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.function.BiFunction
import java.util.function.BiPredicate

class OutboxRoutingRuleTest {
    private val metadata =
        OutboxRecordMetadata(
            key = "order-123",
            handlerId = "test-handler",
            createdAt = Instant.now(),
            context = mapOf("tenant" to "acme"),
        )

    @Test
    fun `builds rule with static target`() {
        val rule =
            OutboxRoutingRule
                .builder(OutboxPayloadSelector.type(String::class.java))
                .target("my-topic")
                .build()

        assertThat(rule.target("payload", metadata)).isEqualTo("my-topic")
    }

    @Test
    fun `builds rule with dynamic target`() {
        val rule =
            OutboxRoutingRule
                .builder(OutboxPayloadSelector.type(OrderEvent::class.java))
                .target { payload, _ -> "orders-${(payload as OrderEvent).type}" }
                .build()

        val payload = OrderEvent("created")
        assertThat(rule.target(payload, metadata)).isEqualTo("orders-created")
    }

    @Test
    fun `key defaults to metadata key`() {
        val rule =
            OutboxRoutingRule
                .builder(OutboxPayloadSelector.type(String::class.java))
                .target("my-topic")
                .build()

        assertThat(rule.key("payload", metadata)).isEqualTo("order-123")
    }

    @Test
    fun `builds rule with custom key extractor`() {
        val rule =
            OutboxRoutingRule
                .builder(OutboxPayloadSelector.type(OrderEvent::class.java))
                .target("orders")
                .key { payload, _ -> (payload as OrderEvent).type }
                .build()

        val payload = OrderEvent("created")
        assertThat(rule.key(payload, metadata)).isEqualTo("created")
    }

    @Test
    fun `headers defaults to empty map`() {
        val rule =
            OutboxRoutingRule
                .builder(OutboxPayloadSelector.type(String::class.java))
                .target("my-topic")
                .build()

        assertThat(rule.headers("payload", metadata)).isEmpty()
    }

    @Test
    fun `builds rule with custom headers provider`() {
        val rule =
            OutboxRoutingRule
                .builder(OutboxPayloadSelector.type(String::class.java))
                .target("my-topic")
                .headers { _, meta -> meta.context }
                .build()

        assertThat(rule.headers("payload", metadata)).containsEntry("tenant", "acme")
    }

    @Test
    fun `selector is accessible`() {
        val selector = OutboxPayloadSelector.type(OrderEvent::class.java)
        val rule =
            OutboxRoutingRule
                .builder(selector)
                .target("orders")
                .build()

        assertThat(rule.selector).isSameAs(selector)
    }

    @Test
    fun `throws when target not configured`() {
        assertThatThrownBy {
            OutboxRoutingRule
                .builder(OutboxPayloadSelector.type(String::class.java))
                .build()
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Target must be configured")
    }

    @Test
    fun `works with BiFunction for Java compatibility`() {
        val rule =
            OutboxRoutingRule
                .builder(OutboxPayloadSelector.type(String::class.java))
                .target(BiFunction { _, meta -> "topic-${meta.key}" })
                .key(BiFunction { payload, _ -> payload.toString() })
                .headers(BiFunction { _, _ -> mapOf("x-custom" to "value") })
                .build()

        assertThat(rule.target("test", metadata)).isEqualTo("topic-order-123")
        assertThat(rule.key("test", metadata)).isEqualTo("test")
        assertThat(rule.headers("test", metadata)).containsEntry("x-custom", "value")
    }

    @Test
    fun `mapping defaults to identity`() {
        val rule =
            OutboxRoutingRule
                .builder(OutboxPayloadSelector.type(String::class.java))
                .target("my-topic")
                .build()

        val payload = "original-payload"
        assertThat(rule.mapping(payload, metadata)).isSameAs(payload)
    }

    @Test
    fun `builds rule with custom mapping`() {
        val rule =
            OutboxRoutingRule
                .builder(OutboxPayloadSelector.type(OrderEvent::class.java))
                .target("orders")
                .mapping { payload, _ -> (payload as OrderEvent).type.uppercase() }
                .build()

        val payload = OrderEvent("created")
        assertThat(rule.mapping(payload, metadata)).isEqualTo("CREATED")
    }

    @Test
    fun `mapping works with BiFunction for Java compatibility`() {
        val rule =
            OutboxRoutingRule
                .builder(OutboxPayloadSelector.type(OrderEvent::class.java))
                .target("orders")
                .mapping(BiFunction { payload, _ -> mapOf("event" to (payload as OrderEvent).type) })
                .build()

        val payload = OrderEvent("created")
        assertThat(rule.mapping(payload, metadata)).isEqualTo(mapOf("event" to "created"))
    }

    @Test
    fun `mapping can transform to different type`() {
        data class PublicOrderEvent(
            val eventType: String,
            val key: String,
        )

        val rule =
            OutboxRoutingRule
                .builder(OutboxPayloadSelector.type(OrderEvent::class.java))
                .target("orders")
                .mapping { payload, meta ->
                    PublicOrderEvent(
                        eventType = (payload as OrderEvent).type,
                        key = meta.key,
                    )
                }.build()

        val payload = OrderEvent("created")
        val mapped = rule.mapping(payload, metadata) as PublicOrderEvent
        assertThat(mapped.eventType).isEqualTo("created")
        assertThat(mapped.key).isEqualTo("order-123")
    }

    @Test
    fun `filter defaults to true`() {
        val rule =
            OutboxRoutingRule
                .builder(OutboxPayloadSelector.type(String::class.java))
                .target("my-topic")
                .build()

        assertThat(rule.filter("payload", metadata)).isTrue()
    }

    @Test
    fun `builds rule with custom filter`() {
        val rule =
            OutboxRoutingRule
                .builder(OutboxPayloadSelector.type(OrderEvent::class.java))
                .target("orders")
                .filter { payload, _ -> (payload as OrderEvent).type != "cancelled" }
                .build()

        assertThat(rule.filter(OrderEvent("created"), metadata)).isTrue()
        assertThat(rule.filter(OrderEvent("cancelled"), metadata)).isFalse()
    }

    @Test
    fun `filter works with BiPredicate for Java compatibility`() {
        val rule =
            OutboxRoutingRule
                .builder(OutboxPayloadSelector.type(OrderEvent::class.java))
                .target("orders")
                .filter(BiPredicate { _, meta -> meta.context["tenant"] == "acme" })
                .build()

        assertThat(rule.filter(OrderEvent("created"), metadata)).isTrue()

        val otherMetadata =
            OutboxRecordMetadata(
                key = "order-123",
                handlerId = "test-handler",
                createdAt = Instant.now(),
                context = mapOf("tenant" to "other"),
            )
        assertThat(rule.filter(OrderEvent("created"), otherMetadata)).isFalse()
    }

    // Test fixture
    data class OrderEvent(
        val type: String,
    )
}
