package io.namastack.outbox.handler

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

@DisplayName("OutboxRecordMetadata")
class OutboxRecordMetadataTest {
    private val createdAt = Instant.parse("2026-05-29T12:00:00Z")

    @Test
    fun `defaults to first attempt for existing constructor`() {
        val metadata =
            OutboxRecordMetadata(
                key = "test-key",
                handlerId = "test-handler",
                createdAt = createdAt,
                context = emptyMap(),
            )

        assertThat(metadata.failureCount).isZero()
        assertThat(metadata.attempt).isEqualTo(1)
        assertThat(metadata.isRetry).isFalse()
    }

    @Test
    fun `exposes retry state from failure count`() {
        val metadata =
            OutboxRecordMetadata(
                key = "test-key",
                handlerId = "test-handler",
                createdAt = createdAt,
                context = emptyMap(),
                failureCount = 2,
            )

        assertThat(metadata.failureCount).isEqualTo(2)
        assertThat(metadata.attempt).isEqualTo(3)
        assertThat(metadata.isRetry).isTrue()
    }

    @Test
    fun `copy keeps existing constructor behavior`() {
        val metadata =
            OutboxRecordMetadata(
                key = "test-key",
                handlerId = "test-handler",
                createdAt = createdAt,
                context = emptyMap(),
                failureCount = 2,
            )

        val copy = metadata.copy(key = "other-key")

        assertThat(copy.key).isEqualTo("other-key")
        assertThat(copy.failureCount).isZero()
        assertThat(copy.attempt).isEqualTo(1)
        assertThat(copy.isRetry).isFalse()
    }
}
