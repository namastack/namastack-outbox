package io.namastack.outbox.routing.selector

import io.namastack.outbox.handler.OutboxRecordMetadata
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class PredicateSelectorTest {
    private val metadata =
        OutboxRecordMetadata(
            key = "test-key",
            handlerId = "test-handler",
            createdAt = Instant.now(),
            context = mapOf("priority" to "high", "tenant" to "acme"),
        )

    @Test
    fun `matches returns true when predicate returns true`() {
        val selector =
            OutboxPayloadSelector.predicate { _, meta ->
                meta.context["priority"] == "high"
            }

        assertThat(selector.matches("any-payload", metadata)).isTrue()
    }

    @Test
    fun `matches returns false when predicate returns false`() {
        val selector =
            OutboxPayloadSelector.predicate { _, meta ->
                meta.context["priority"] == "low"
            }

        assertThat(selector.matches("any-payload", metadata)).isFalse()
    }

    @Test
    fun `matches can inspect payload`() {
        val selector =
            OutboxPayloadSelector.predicate { payload, _ ->
                payload is String && payload.startsWith("order-")
            }

        assertThat(selector.matches("order-123", metadata)).isTrue()
        assertThat(selector.matches("payment-456", metadata)).isFalse()
    }

    @Test
    fun `matches can combine payload and metadata conditions`() {
        val selector =
            OutboxPayloadSelector.predicate { payload, meta ->
                payload is String && meta.context["tenant"] == "acme"
            }

        assertThat(selector.matches("any-payload", metadata)).isTrue()
    }

    @Test
    fun `matches works with BiPredicate for Java compatibility`() {
        val selector =
            OutboxPayloadSelector.predicate(
                java.util.function.BiPredicate { _, meta -> meta.key == "test-key" },
            )

        assertThat(selector.matches("any-payload", metadata)).isTrue()
    }
}
