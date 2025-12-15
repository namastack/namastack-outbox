package io.namastack.outbox

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.util.UUID

class OutboxRecordEntityMapperTest {
    private val serializer = JacksonOutboxPayloadSerializer(JsonMapper())
    private val mapper = OutboxRecordEntityMapper(serializer)

    data class OrderCreatedEvent(
        val orderId: String,
        val amount: Double,
    )

    data class PaymentProcessedEvent(
        val paymentId: String,
        val status: String,
    )

    @Test
    fun `should map OutboxRecord to OutboxRecordEntity with OrderCreatedEvent payload`() {
        val now = OffsetDateTime.now()
        val completedAt = now.plusMinutes(5)
        val nextRetryAt = now.plusMinutes(10)
        val event = OrderCreatedEvent(orderId = "123", amount = 100.50)

        val record =
            OutboxRecord.restore(
                id = "test-id",
                recordKey = "record-123",
                payload = event,
                attributes = mapOf("a" to "b", "x" to "y"),
                partition = 1,
                createdAt = now,
                status = OutboxRecordStatus.NEW,
                completedAt = completedAt,
                failureCount = 3,
                nextRetryAt = nextRetryAt,
                handlerId = "handlerId",
            )

        val entity = mapper.map(record)

        assertThat(entity.id).isEqualTo("test-id")
        assertThat(entity.status).isEqualTo(OutboxRecordStatus.NEW)
        assertThat(entity.recordKey).isEqualTo("record-123")
        assertThat(entity.recordType).isEqualTo("io.namastack.outbox.OutboxRecordEntityMapperTest\$OrderCreatedEvent")
        assertThat(entity.payload).contains("orderId", "123", "100.5")
        assertThat(entity.attributes).contains("a", "b", "x", "y")
        assertThat(entity.createdAt).isEqualTo(now)
        assertThat(entity.completedAt).isEqualTo(completedAt)
        assertThat(entity.failureCount).isEqualTo(3)
        assertThat(entity.nextRetryAt).isEqualTo(nextRetryAt)
    }

    @Test
    fun `should map OutboxRecord to OutboxRecordEntity with null completedAt`() {
        val now = OffsetDateTime.now()
        val event = PaymentProcessedEvent(paymentId = "pay-456", status = "completed")

        val record =
            OutboxRecord.restore(
                id = "test-id-2",
                recordKey = "record-456",
                payload = event,
                attributes = mapOf("a" to "b", "x" to "y"),
                partition = 1,
                createdAt = now,
                status = OutboxRecordStatus.NEW,
                completedAt = null,
                failureCount = 0,
                nextRetryAt = now,
                handlerId = "handlerId",
            )

        val entity = mapper.map(record)

        assertThat(entity.id).isEqualTo("test-id-2")
        assertThat(entity.status).isEqualTo(OutboxRecordStatus.NEW)
        assertThat(entity.recordKey).isEqualTo("record-456")
        assertThat(
            entity.recordType,
        ).isEqualTo("io.namastack.outbox.OutboxRecordEntityMapperTest\$PaymentProcessedEvent")
        assertThat(entity.payload).contains("pay-456", "completed")
        assertThat(entity.attributes).contains("a", "b", "x", "y")
        assertThat(entity.createdAt).isEqualTo(now)
        assertThat(entity.completedAt).isNull()
        assertThat(entity.failureCount).isEqualTo(0)
    }

    @Test
    fun `should map OutboxRecord with FAILED status`() {
        val now = OffsetDateTime.now()
        val event = OrderCreatedEvent(orderId = "fail-789", amount = 50.0)

        val record =
            OutboxRecord.restore(
                id = UUID.randomUUID().toString(),
                recordKey = "failed-record",
                payload = event,
                attributes = mapOf("a" to "b", "x" to "y"),
                partition = 1,
                createdAt = now,
                status = OutboxRecordStatus.FAILED,
                completedAt = null,
                failureCount = 5,
                nextRetryAt = now,
                handlerId = "handlerId",
            )

        val entity = mapper.map(record)

        assertThat(entity.status).isEqualTo(OutboxRecordStatus.FAILED)
        assertThat(entity.failureCount).isEqualTo(5)
        assertThat(entity.recordKey).isEqualTo("failed-record")
        assertThat(entity.recordType).isEqualTo("io.namastack.outbox.OutboxRecordEntityMapperTest\$OrderCreatedEvent")
    }

    // ...existing code...

    @Test
    fun `should maintain serialization integrity with different event types`() {
        val now = OffsetDateTime.now()
        val orderEvent = OrderCreatedEvent(orderId = "order-555", amount = 999.99)
        val paymentEvent = PaymentProcessedEvent(paymentId = "pay-555", status = "approved")

        val orderRecord =
            OutboxRecord.restore(
                id = "order-test",
                recordKey = "order-key",
                payload = orderEvent,
                attributes = mapOf("a" to "b"),
                partition = 1,
                createdAt = now,
                status = OutboxRecordStatus.NEW,
                completedAt = null,
                failureCount = 0,
                nextRetryAt = now,
                handlerId = "handlerId",
            )

        val paymentRecord =
            OutboxRecord.restore(
                id = "payment-test",
                recordKey = "payment-key",
                payload = paymentEvent,
                attributes = mapOf("x" to "y"),
                partition = 1,
                createdAt = now,
                status = OutboxRecordStatus.NEW,
                completedAt = null,
                failureCount = 0,
                nextRetryAt = now,
                handlerId = "handlerId",
            )

        val orderEntity = mapper.map(orderRecord)
        val paymentEntity = mapper.map(paymentRecord)

        assertThat(
            orderEntity.recordType,
        ).isEqualTo("io.namastack.outbox.OutboxRecordEntityMapperTest\$OrderCreatedEvent")
        assertThat(orderEntity.payload).contains("order-555")
        assertThat(
            paymentEntity.recordType,
        ).isEqualTo("io.namastack.outbox.OutboxRecordEntityMapperTest\$PaymentProcessedEvent")
        assertThat(paymentEntity.payload).contains("pay-555")
    }

    @Test
    fun `should map all status types correctly`() {
        val now = OffsetDateTime.now()
        val event = OrderCreatedEvent(orderId = "status-test", amount = 1.0)

        for (status in OutboxRecordStatus.entries) {
            val record =
                OutboxRecord.restore(
                    id = "status-test-${status.name}",
                    recordKey = "status-record",
                    payload = event,
                    attributes = mapOf("a" to "b", "x" to "y"),
                    partition = 1,
                    createdAt = now,
                    status = status,
                    completedAt = null,
                    failureCount = 0,
                    nextRetryAt = now,
                    handlerId = "handlerId",
                )

            val entity = mapper.map(record)

            assertThat(entity.status).isEqualTo(status)
            assertThat(
                entity.recordType,
            ).isEqualTo("io.namastack.outbox.OutboxRecordEntityMapperTest\$OrderCreatedEvent")
        }
    }
}
