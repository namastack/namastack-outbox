package io.namastack.outbox

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.time.OffsetDateTime

class JdbcOutboxRecordEntityMapperTest {
    private val innerJsonMapper = jsonMapper { addModule(kotlinModule()) }
    private val serializer = JacksonOutboxPayloadSerializer(innerJsonMapper)
    private val mapper = JdbcOutboxRecordEntityMapper(serializer)

    data class OrderCreatedEvent(
        val orderId: String,
        val amount: Double,
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
                    failureException = null,
                )

            val entity = mapper.map(record)

            assertThat(entity.id).isEqualTo("test-id")
            assertThat(entity.status).isEqualTo(OutboxRecordStatus.NEW)
            assertThat(entity.recordKey).isEqualTo("record-123")
            assertThat(
                entity.recordType,
            ).isEqualTo("io.namastack.outbox.JdbcOutboxRecordEntityMapperTest\$OrderCreatedEvent")
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
                    failureException = null,
                )

            val entity = mapper.map(record)

            assertThat(entity.id).isEqualTo("test-id")
            assertThat(entity.context).isEqualTo("{\"a\":\"b\",\"x\":\"y\"}")
        }

        @Test
        fun `should map empty context to null`() {
            val now = OffsetDateTime.now()
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
                    completedAt = null,
                    failureCount = 0,
                    failureReason = null,
                    nextRetryAt = now,
                    handlerId = "handlerId",
                    failureException = null,
                )

            val entity = mapper.map(record)

            assertThat(entity.context).isNull()
        }
    }

    @Nested
    @DisplayName("map OutboxRecordEntity to OutboxRecord")
    inner class MapEntityToOutboxRecordTests {
        @Test
        fun `should deserialize event payload correctly`() {
            val now = OffsetDateTime.now()
            val entity =
                JdbcOutboxRecordEntity(
                    id = "test-id",
                    status = OutboxRecordStatus.NEW,
                    recordKey = "record-123",
                    recordType = "io.namastack.outbox.JdbcOutboxRecordEntityMapperTest\$OrderCreatedEvent",
                    payload = "{\"orderId\":\"123\",\"amount\":100.5}",
                    context = null,
                    partitionNo = 1,
                    createdAt = now,
                    completedAt = null,
                    failureCount = 0,
                    failureReason = null,
                    nextRetryAt = now,
                    handlerId = "handlerId",
                )

            val record = mapper.map(entity)

            assertThat(record.id).isEqualTo("test-id")
            assertThat(record.status).isEqualTo(OutboxRecordStatus.NEW)
            assertThat(record.key).isEqualTo("record-123")
            assertThat(record.payload).isInstanceOf(OrderCreatedEvent::class.java)
            val event = record.payload as OrderCreatedEvent
            assertThat(event.orderId).isEqualTo("123")
            assertThat(event.amount).isEqualTo(100.5)
            assertThat(record.context).isEmpty()
        }

        @Test
        fun `should deserialize context correctly`() {
            val now = OffsetDateTime.now()
            val entity =
                JdbcOutboxRecordEntity(
                    id = "test-id",
                    status = OutboxRecordStatus.NEW,
                    recordKey = "record-123",
                    recordType = "io.namastack.outbox.JdbcOutboxRecordEntityMapperTest\$OrderCreatedEvent",
                    payload = "{\"orderId\":\"123\",\"amount\":100.5}",
                    context = "{\"a\":\"b\",\"x\":\"y\"}",
                    partitionNo = 1,
                    createdAt = now,
                    completedAt = null,
                    failureCount = 0,
                    failureReason = null,
                    nextRetryAt = now,
                    handlerId = "handlerId",
                )

            val record = mapper.map(entity)

            assertThat(record.context).containsEntry("a", "b")
            assertThat(record.context).containsEntry("x", "y")
        }

        @Test
        fun `should map null context to empty map`() {
            val now = OffsetDateTime.now()
            val entity =
                JdbcOutboxRecordEntity(
                    id = "test-id",
                    status = OutboxRecordStatus.NEW,
                    recordKey = "record-123",
                    recordType = "io.namastack.outbox.JdbcOutboxRecordEntityMapperTest\$OrderCreatedEvent",
                    payload = "{\"orderId\":\"123\",\"amount\":100.5}",
                    context = null,
                    partitionNo = 1,
                    createdAt = now,
                    completedAt = null,
                    failureCount = 0,
                    failureReason = null,
                    nextRetryAt = now,
                    handlerId = "handlerId",
                )

            val record = mapper.map(entity)

            assertThat(record.context).isEmpty()
        }
    }
}
