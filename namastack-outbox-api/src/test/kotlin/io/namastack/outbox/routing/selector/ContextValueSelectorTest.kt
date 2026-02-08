package io.namastack.outbox.routing.selector

import io.namastack.outbox.handler.OutboxRecordMetadata
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class ContextValueSelectorTest {
    @Test
    fun `matches returns true when context contains expected value`() {
        val selector = OutboxPayloadSelector.contextValue("tenant", "acme")
        val metadata = createMetadata(mapOf("tenant" to "acme"))

        assertThat(selector.matches("any-payload", metadata)).isTrue()
    }

    @Test
    fun `matches returns false when context value differs`() {
        val selector = OutboxPayloadSelector.contextValue("tenant", "acme")
        val metadata = createMetadata(mapOf("tenant" to "other"))

        assertThat(selector.matches("any-payload", metadata)).isFalse()
    }

    @Test
    fun `matches returns false when context key is missing`() {
        val selector = OutboxPayloadSelector.contextValue("tenant", "acme")
        val metadata = createMetadata(emptyMap())

        assertThat(selector.matches("any-payload", metadata)).isFalse()
    }

    @Test
    fun `matches is case sensitive for values`() {
        val selector = OutboxPayloadSelector.contextValue("tenant", "ACME")
        val metadata = createMetadata(mapOf("tenant" to "acme"))

        assertThat(selector.matches("any-payload", metadata)).isFalse()
    }

    @Test
    fun `matches is case sensitive for keys`() {
        val selector = OutboxPayloadSelector.contextValue("Tenant", "acme")
        val metadata = createMetadata(mapOf("tenant" to "acme"))

        assertThat(selector.matches("any-payload", metadata)).isFalse()
    }

    @Test
    fun `matches works with empty string value`() {
        val selector = OutboxPayloadSelector.contextValue("flag", "")
        val metadata = createMetadata(mapOf("flag" to ""))

        assertThat(selector.matches("any-payload", metadata)).isTrue()
    }

    @Test
    fun `matches ignores payload - only checks context`() {
        val selector = OutboxPayloadSelector.contextValue("priority", "high")
        val metadata = createMetadata(mapOf("priority" to "high"))

        assertThat(selector.matches("string-payload", metadata)).isTrue()
        assertThat(selector.matches(123, metadata)).isTrue()
        assertThat(selector.matches(listOf(1, 2, 3), metadata)).isTrue()
    }

    private fun createMetadata(context: Map<String, String>) =
        OutboxRecordMetadata(
            key = "test-key",
            handlerId = "test-handler",
            createdAt = Instant.now(),
            context = context,
        )
}
