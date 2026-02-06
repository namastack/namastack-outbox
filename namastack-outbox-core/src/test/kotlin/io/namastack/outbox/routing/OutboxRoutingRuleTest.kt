package io.namastack.outbox.routing

import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.routing.selector.OutboxPayloadSelector
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.function.BiFunction

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

    // Test fixture
    data class OrderEvent(
        val type: String,
    )
}
