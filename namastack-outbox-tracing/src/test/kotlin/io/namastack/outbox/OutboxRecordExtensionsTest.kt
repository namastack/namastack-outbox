package io.namastack.outbox

import io.namastack.outbox.OutboxRecordTestFactory.outboxRecord
import io.opentelemetry.api.common.AttributeKey.stringKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset

class OutboxRecordExtensionsTest {
    @Test
    fun `toAttributes includes all record metadata`() {
        val createdAt = OffsetDateTime.now(ZoneOffset.UTC)
        val nextRetryAt = createdAt.plusMinutes(5)
        val completedAt = createdAt.plusMinutes(10)

        val record =
            outboxRecord(
                id = "rec-123",
                recordKey = "order-456",
                handlerId = "OrderHandler#handle(OrderEvent)",
                partition = 42,
                createdAt = createdAt,
                nextRetryAt = nextRetryAt,
                failureCount = 3,
                completedAt = completedAt,
                status = OutboxRecordStatus.COMPLETED,
            )

        val attributes = record.toAttributes()

        assertThat(attributes.get(stringKey("outbox.record.id"))).isEqualTo("rec-123")
        assertThat(attributes.get(stringKey("outbox.record.key"))).isEqualTo("order-456")
        assertThat(attributes.get(stringKey("outbox.handler"))).isEqualTo("OrderHandler#handle(OrderEvent)")
        assertThat(attributes.get(stringKey("outbox.status"))).isEqualTo("COMPLETED")
        assertThat(attributes.get(stringKey("outbox.partition"))).isEqualTo("42")
        assertThat(attributes.get(stringKey("outbox.failure.count"))).isEqualTo("3")
        assertThat(attributes.get(stringKey("outbox.created_at"))).isEqualTo(createdAt.toString())
        assertThat(attributes.get(stringKey("outbox.retry.next_at"))).isEqualTo(nextRetryAt.toString())
        assertThat(attributes.get(stringKey("outbox.completed_at"))).isEqualTo(completedAt.toString())
    }

    @Test
    fun `toAttributes includes failure exception when present`() {
        val exception = RuntimeException("Handler failed")
        val record =
            outboxRecord(
                id = "rec-123",
                recordKey = "order-456",
                handlerId = "OrderHandler",
                failureException = exception,
            )

        val attributes = record.toAttributes()

        assertThat(attributes.get(stringKey("outbox.failure.exception")))
            .isEqualTo("Handler failed")
    }

    @Test
    fun `toAttributes includes failure reason when present`() {
        val record =
            outboxRecord(
                id = "rec-123",
                recordKey = "order-456",
                handlerId = "OrderHandler",
                failureReason = "VALIDATION_ERROR",
            )

        val attributes = record.toAttributes()

        assertThat(attributes.get(stringKey("outbox.failure.reason")))
            .isEqualTo("VALIDATION_ERROR")
    }

    @Test
    fun `toAttributes excludes optional attributes when not present`() {
        val record =
            outboxRecord(
                id = "rec-123",
                recordKey = "order-456",
                handlerId = "OrderHandler",
                failureReason = null,
                failureException = null,
            )

        val attributes = record.toAttributes()

        assertThat(attributes.asMap()).doesNotContainKey(stringKey("outbox.failure.exception"))
        assertThat(attributes.asMap()).doesNotContainKey(stringKey("outbox.failure.reason"))
    }

    @Test
    fun `toAttributes returns non-empty Attributes`() {
        val record =
            outboxRecord(
                id = "rec-123",
                recordKey = "order-456",
                handlerId = "OrderHandler",
            )

        val attributes = record.toAttributes()

        assertThat(attributes).isNotNull
        assertThat(attributes.isEmpty).isFalse()
    }
}
