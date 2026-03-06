package io.namastack.outbox.routing.selector

import io.namastack.outbox.handler.OutboxRecordMetadata
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class AllSelectorTest {
    private val metadata =
        OutboxRecordMetadata(
            key = "test-key",
            handlerId = "test-handler",
            createdAt = Instant.now(),
            context = emptyMap(),
        )

    @Test
    fun `all matches any string payload`() {
        val selector = OutboxPayloadSelector.all()

        assertThat(selector.matches("any string", metadata)).isTrue()
    }

    @Test
    fun `all matches any object payload`() {
        val selector = OutboxPayloadSelector.all()

        assertThat(selector.matches(Any(), metadata)).isTrue()
    }

    @Test
    fun `all matches any number payload`() {
        val selector = OutboxPayloadSelector.all()

        assertThat(selector.matches(123, metadata)).isTrue()
        assertThat(selector.matches(45.67, metadata)).isTrue()
    }

    @Test
    fun `all matches with any metadata`() {
        val selector = OutboxPayloadSelector.all()

        val metadataWithContext =
            OutboxRecordMetadata(
                key = "different-key",
                handlerId = "other-handler",
                createdAt = Instant.now(),
                context = mapOf("tenant" to "acme", "priority" to "high"),
            )

        assertThat(selector.matches("payload", metadataWithContext)).isTrue()
    }

    @Test
    fun `all matches custom data class`() {
        data class CustomEvent(
            val id: String,
            val data: String,
        )

        val selector = OutboxPayloadSelector.all()

        assertThat(selector.matches(CustomEvent("1", "test"), metadata)).isTrue()
    }

    @Test
    fun `all matches null-like edge cases`() {
        val selector = OutboxPayloadSelector.all()

        assertThat(selector.matches("", metadata)).isTrue()
        assertThat(selector.matches(emptyList<String>(), metadata)).isTrue()
        assertThat(selector.matches(emptyMap<String, String>(), metadata)).isTrue()
    }
}
