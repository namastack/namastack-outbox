package io.namastack.outbox

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.time.OffsetDateTime
import java.util.UUID

class OutboxRecordEntityMapperTest {
    private val innerJsonMapper = jsonMapper { addModule(kotlinModule()) }
    private val serializer = JacksonOutboxPayloadSerializer(innerJsonMapper)
    private val mapper = OutboxRecordEntityMapper(serializer)

    data class OrderCreatedEvent(
        val orderId: String,
        val amount: Double,
    )

    data class PaymentProcessedEvent(
        val paymentId: String,
        val status: String,
    )

    @Nested
    @DisplayName("map OutboxRecord to OutboxRecordEntity")
    inner class MapOutboxRecordTests {
        @Test
        fun `should map OrderCreatedEvent payload`() {
            val now = OffsetDateTime.now()
            val completedAt = now.plusMinutes(5)
            val nextRetryAt = now.plusMinutes(10)
            val event = OrderCreatedEvent(orderId = "123", amount = 100.50)

            val record =
                OutboxRecord.restore(
                    id = "test-id",
                    recordKey = "record-123",
                    payload = event,
                    context = emptyMap(),
                    partition = 1,
                    createdAt = now,
                    status = OutboxRecordStatus.NEW,
                    completedAt = completedAt,
                    failureCount = 3,
                    failureReason = null,
                    nextRetryAt = nextRetryAt,
                    handlerId = "handlerId",
                )

            val entity = mapper.map(record)

            assertThat(entity.id).isEqualTo("test-id")
            assertThat(entity.status).isEqualTo(OutboxRecordStatus.NEW)
            assertThat(entity.recordKey).isEqualTo("record-123")
            assertThat(
                entity.recordType,
            ).isEqualTo("io.namastack.outbox.OutboxRecordEntityMapperTest\$OrderCreatedEvent")
            assertThat(entity.payload).contains("orderId", "123", "100.5")
            assertThat(entity.context).isNull()
            assertThat(entity.createdAt).isEqualTo(now)
            assertThat(entity.completedAt).isEqualTo(completedAt)
            assertThat(entity.failureCount).isEqualTo(3)
            assertThat(entity.nextRetryAt).isEqualTo(nextRetryAt)
        }

        @Test
        fun `should map provided context`() {
            val now = OffsetDateTime.now()
            val completedAt = now.plusMinutes(5)
            val nextRetryAt = now.plusMinutes(10)
            val event = OrderCreatedEvent(orderId = "123", amount = 100.50)

            val record =
                OutboxRecord.restore(
                    id = "test-id",
                    recordKey = "record-123",
                    payload = event,
                    context = mapOf("a" to "b", "x" to "y"),
                    partition = 1,
                    createdAt = now,
                    status = OutboxRecordStatus.NEW,
                    completedAt = completedAt,
                    failureCount = 3,
                    failureReason = null,
                    nextRetryAt = nextRetryAt,
                    handlerId = "handlerId",
                )

            val entity = mapper.map(record)

            assertThat(entity.id).isEqualTo("test-id")
            assertThat(entity.context).isEqualTo("{\"a\":\"b\",\"x\":\"y\"}")
        }

        @Test
        fun `should map null completedAt`() {
            val now = OffsetDateTime.now()
            val event = PaymentProcessedEvent(paymentId = "pay-456", status = "completed")

            val record =
                OutboxRecord.restore(
                    id = "test-id-2",
                    recordKey = "record-456",
                    payload = event,
                    context = emptyMap(),
                    partition = 1,
                    createdAt = now,
                    status = OutboxRecordStatus.NEW,
                    completedAt = null,
                    failureCount = 0,
                    failureReason = null,
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
            assertThat(entity.createdAt).isEqualTo(now)
            assertThat(entity.completedAt).isNull()
            assertThat(entity.failureCount).isEqualTo(0)
        }

        @Test
        fun `should map FAILED status`() {
            val now = OffsetDateTime.now()
            val event = OrderCreatedEvent(orderId = "fail-789", amount = 50.0)

            val record =
                OutboxRecord.restore(
                    id = UUID.randomUUID().toString(),
                    recordKey = "failed-record",
                    payload = event,
                    context = emptyMap(),
                    partition = 1,
                    createdAt = now,
                    status = OutboxRecordStatus.FAILED,
                    completedAt = null,
                    failureCount = 5,
                    failureReason = "Processing error",
                    nextRetryAt = now,
                    handlerId = "handlerId",
                )

            val entity = mapper.map(record)

            assertThat(entity.status).isEqualTo(OutboxRecordStatus.FAILED)
            assertThat(entity.failureCount).isEqualTo(5)
            assertThat(entity.failureReason).isEqualTo("Processing error")
            assertThat(entity.recordKey).isEqualTo("failed-record")
            assertThat(
                entity.recordType,
            ).isEqualTo("io.namastack.outbox.OutboxRecordEntityMapperTest\$OrderCreatedEvent")
        }

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
                    context = emptyMap(),
                    partition = 1,
                    createdAt = now,
                    status = OutboxRecordStatus.NEW,
                    completedAt = null,
                    failureCount = 0,
                    failureReason = null,
                    nextRetryAt = now,
                    handlerId = "handlerId",
                )

            val paymentRecord =
                OutboxRecord.restore(
                    id = "payment-test",
                    recordKey = "payment-key",
                    payload = paymentEvent,
                    context = emptyMap(),
                    partition = 1,
                    createdAt = now,
                    status = OutboxRecordStatus.NEW,
                    completedAt = null,
                    failureCount = 0,
                    failureReason = null,
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
                        context = emptyMap(),
                        partition = 1,
                        createdAt = now,
                        status = status,
                        completedAt = null,
                        failureCount = 0,
                        failureReason = null,
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

    @Nested
    @DisplayName("map OutboxRecordEntity to OutboxRecord")
    inner class MapOutboxRecordEntityTests {
        @Test
        fun `should map OrderCreatedEvent payload`() {
            val now = OffsetDateTime.now()
            val completedAt = now.plusMinutes(5)
            val nextRetryAt = now.plusMinutes(10)
            val event = OrderCreatedEvent(orderId = "123", amount = 100.50)

            val entity =
                OutboxRecordEntity(
                    id = "test-id",
                    recordKey = "record-123",
                    recordType = "io.namastack.outbox.OutboxRecordEntityMapperTest\$OrderCreatedEvent",
                    payload = serializer.serialize(event),
                    context = null,
                    partitionNo = 1,
                    createdAt = now,
                    status = OutboxRecordStatus.NEW,
                    completedAt = completedAt,
                    failureCount = 3,
                    failureReason = null,
                    nextRetryAt = nextRetryAt,
                    handlerId = "handlerId",
                )

            val record = mapper.map(entity)

            assertThat(record.id).isEqualTo("test-id")
            assertThat(record.key).isEqualTo("record-123")
            assertThat(record.payload)
                .isInstanceOf(OrderCreatedEvent::class.java)
                .isEqualTo(event)
            assertThat(record.context).isEmpty()
            assertThat(record.partition).isEqualTo(1)
            assertThat(record.createdAt).isEqualTo(now)
            assertThat(record.status).isEqualTo(OutboxRecordStatus.NEW)
            assertThat(record.completedAt).isEqualTo(completedAt)
            assertThat(record.failureCount).isEqualTo(3)
            assertThat(record.nextRetryAt).isEqualTo(nextRetryAt)
            assertThat(record.handlerId).isEqualTo("handlerId")
        }

        @Test
        fun `should map provided context`() {
            val now = OffsetDateTime.now()
            val completedAt = now.plusMinutes(5)
            val nextRetryAt = now.plusMinutes(10)
            val event = OrderCreatedEvent(orderId = "123", amount = 100.50)

            val entity =
                OutboxRecordEntity(
                    id = "test-id",
                    recordKey = "record-123",
                    recordType = "io.namastack.outbox.OutboxRecordEntityMapperTest\$OrderCreatedEvent",
                    payload = serializer.serialize(event),
                    context = "{\"a\":\"b\",\"x\":\"y\"}",
                    partitionNo = 1,
                    createdAt = now,
                    status = OutboxRecordStatus.NEW,
                    completedAt = completedAt,
                    failureCount = 3,
                    failureReason = null,
                    nextRetryAt = nextRetryAt,
                    handlerId = "handlerId",
                )

            val record = mapper.map(entity)

            assertThat(record.id).isEqualTo("test-id")
            assertThat(record.context)
                .hasSize(2)
                .contains(
                    entry("a", "b"),
                    entry("x", "y"),
                )
        }

        @Test
        fun `should map null completedAt`() {
            val now = OffsetDateTime.now()
            val event = PaymentProcessedEvent(paymentId = "pay-456", status = "completed")

            val entity =
                OutboxRecordEntity(
                    id = "test-id-2",
                    recordKey = "record-456",
                    recordType = "io.namastack.outbox.OutboxRecordEntityMapperTest\$PaymentProcessedEvent",
                    payload = serializer.serialize(event),
                    context = null,
                    partitionNo = 1,
                    createdAt = now,
                    status = OutboxRecordStatus.NEW,
                    completedAt = null,
                    failureCount = 0,
                    failureReason = null,
                    nextRetryAt = now,
                    handlerId = "handlerId",
                )

            val record = mapper.map(entity)

            assertThat(record.id).isEqualTo("test-id-2")
            assertThat(record.status).isEqualTo(OutboxRecordStatus.NEW)
            assertThat(record.key).isEqualTo("record-456")
            assertThat(record.payload)
                .isInstanceOf(PaymentProcessedEvent::class.java)
                .isEqualTo(event)
            assertThat(record.createdAt).isEqualTo(now)
            assertThat(record.completedAt).isNull()
            assertThat(record.failureCount).isEqualTo(0)
        }

        @Test
        fun `should map FAILED status`() {
            val now = OffsetDateTime.now()
            val event = OrderCreatedEvent(orderId = "fail-789", amount = 50.0)

            val entity =
                OutboxRecordEntity(
                    id = UUID.randomUUID().toString(),
                    recordKey = "failed-record",
                    recordType = "io.namastack.outbox.OutboxRecordEntityMapperTest\$OrderCreatedEvent",
                    payload = serializer.serialize(event),
                    context = null,
                    partitionNo = 1,
                    createdAt = now,
                    status = OutboxRecordStatus.FAILED,
                    completedAt = null,
                    failureCount = 5,
                    failureReason = "Processing error",
                    nextRetryAt = now,
                    handlerId = "handlerId",
                )

            val record = mapper.map(entity)

            assertThat(record.status).isEqualTo(OutboxRecordStatus.FAILED)
            assertThat(record.failureCount).isEqualTo(5)
            assertThat(record.failureReason).isEqualTo("Processing error")
            assertThat(record.key).isEqualTo("failed-record")
            assertThat(record.payload).isInstanceOf(OrderCreatedEvent::class.java)
        }

        @Test
        fun `should maintain deserialization integrity with different event types`() {
            val now = OffsetDateTime.now()
            val orderEvent = OrderCreatedEvent(orderId = "order-555", amount = 999.99)
            val paymentEvent = PaymentProcessedEvent(paymentId = "pay-555", status = "approved")

            val orderEntity =
                OutboxRecordEntity(
                    id = "order-test",
                    recordKey = "order-key",
                    recordType = "io.namastack.outbox.OutboxRecordEntityMapperTest\$OrderCreatedEvent",
                    payload = serializer.serialize(orderEvent),
                    context = null,
                    partitionNo = 1,
                    createdAt = now,
                    status = OutboxRecordStatus.NEW,
                    completedAt = null,
                    failureCount = 0,
                    failureReason = null,
                    nextRetryAt = now,
                    handlerId = "handlerId",
                )

            val paymentEntity =
                OutboxRecordEntity(
                    id = "payment-test",
                    recordKey = "payment-key",
                    recordType = "io.namastack.outbox.OutboxRecordEntityMapperTest\$PaymentProcessedEvent",
                    payload = serializer.serialize(paymentEvent),
                    context = null,
                    partitionNo = 1,
                    createdAt = now,
                    status = OutboxRecordStatus.NEW,
                    completedAt = null,
                    failureCount = 0,
                    failureReason = null,
                    nextRetryAt = now,
                    handlerId = "handlerId",
                )

            val orderRecord = mapper.map(orderEntity)
            val paymentRecord = mapper.map(paymentEntity)

            assertThat(orderRecord.payload).isInstanceOf(OrderCreatedEvent::class.java)
            val orderPayload = orderRecord.payload as OrderCreatedEvent
            assertThat(orderPayload.orderId).isEqualTo("order-555")
            assertThat(orderPayload.amount).isEqualTo(999.99)

            assertThat(paymentRecord.payload).isInstanceOf(PaymentProcessedEvent::class.java)
            val paymentPayload = paymentRecord.payload as PaymentProcessedEvent
            assertThat(paymentPayload.paymentId).isEqualTo("pay-555")
            assertThat(paymentPayload.status).isEqualTo("approved")
        }

        @Test
        fun `should map all status types correctly`() {
            val now = OffsetDateTime.now()
            val event = OrderCreatedEvent(orderId = "status-test", amount = 1.0)

            for (status in OutboxRecordStatus.entries) {
                val entity =
                    OutboxRecordEntity(
                        id = "status-test-${status.name}",
                        recordKey = "status-record",
                        recordType = "io.namastack.outbox.OutboxRecordEntityMapperTest\$OrderCreatedEvent",
                        payload = serializer.serialize(event),
                        context = null,
                        partitionNo = 1,
                        createdAt = now,
                        status = status,
                        completedAt = null,
                        failureCount = 0,
                        failureReason = null,
                        nextRetryAt = now,
                        handlerId = "handlerId",
                    )

                val record = mapper.map(entity)

                assertThat(record.status).isEqualTo(status)
                assertThat(record.payload).isInstanceOf(OrderCreatedEvent::class.java)
            }
        }
    }
}
