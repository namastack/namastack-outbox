package io.namastack.outbox.handler

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

@DisplayName("OutboxRecordMetadata")
class OutboxRecordMetadataTest {
    private val createdAt = Instant.parse("2026-05-29T12:00:00Z")

    @Test
    fun `copies preserve persisted record ID unless explicitly changed`() {
        val metadata =
            OutboxRecordMetadata(
                key = "test-key",
                handlerId = "test-handler",
                createdAt = createdAt,
                context = emptyMap(),
                recordId = "record-id",
            )

        assertThat(metadata.copy(failureCount = 2).recordId).isEqualTo("record-id")
        assertThat(metadata.copy(recordId = "other-id").recordId).isEqualTo("other-id")
        assertThat(metadata.copy(recordId = "other-id").key).isEqualTo("test-key")
        assertThat(metadata.copy(recordId = null).recordId).isNull()
    }

    @Test
    fun `copies preserve processing group and record ID unless explicitly changed`() {
        val metadata =
            OutboxRecordMetadata(
                key = "test-key",
                handlerId = "test-handler",
                createdAt = createdAt,
                context = emptyMap(),
                recordId = "record-id",
                processingGroup = "billing",
            )

        val retry = metadata.copy(failureCount = 2)

        assertThat(retry.processingGroup).isEqualTo("billing")
        assertThat(retry.recordId).isEqualTo("record-id")
        assertThat(retry.attempt).isEqualTo(3)
        assertThat(metadata.copy(recordId = "other-id").processingGroup).isEqualTo("billing")
        assertThat(metadata.copy(processingGroup = "shipping").recordId).isEqualTo("record-id")
        assertThat(metadata.copy(processingGroup = "shipping").processingGroup).isEqualTo("shipping")
    }

    @Test
    fun `defaults to the first attempt without a persisted record ID`() {
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
        assertThat(metadata.recordId).isNull()
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
    fun `copy preserves retry state by default`() {
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
        assertThat(copy.recordId).isEqualTo(metadata.recordId)
        assertThat(copy.failureCount).isEqualTo(2)
        assertThat(copy.attempt).isEqualTo(3)
        assertThat(copy.isRetry).isTrue()
    }
}
