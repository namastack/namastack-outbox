package io.namastack.outbox

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

class OutboxRecordEntityMapperTest {
    @Test
    fun `should map OutboxRecord to OutboxRecordEntity with all properties`() {
        val now = OffsetDateTime.now()
        val completedAt = now.plusMinutes(5)
        val nextRetryAt = now.plusMinutes(10)

        val record =
            OutboxRecord.restore(
                id = "test-id",
                aggregateId = "aggregate-123",
                eventType = "OrderCreated",
                payload = """{"orderId": "123", "amount": 100.50}""",
                partition = 1,
                createdAt = now,
                status = OutboxRecordStatus.NEW,
                completedAt = completedAt,
                retryCount = 3,
                nextRetryAt = nextRetryAt,
            )

        val entity = OutboxRecordEntityMapper.map(record)

        assertThat(entity.id).isEqualTo("test-id")
        assertThat(entity.status).isEqualTo(OutboxRecordStatus.NEW)
        assertThat(entity.aggregateId).isEqualTo("aggregate-123")
        assertThat(entity.eventType).isEqualTo("OrderCreated")
        assertThat(entity.payload).isEqualTo("""{"orderId": "123", "amount": 100.50}""")
        assertThat(entity.createdAt).isEqualTo(now)
        assertThat(entity.completedAt).isEqualTo(completedAt)
        assertThat(entity.retryCount).isEqualTo(3)
        assertThat(entity.nextRetryAt).isEqualTo(nextRetryAt)
    }

    @Test
    fun `should map OutboxRecord to OutboxRecordEntity with null values`() {
        val now = OffsetDateTime.now()

        val record =
            OutboxRecord.restore(
                id = "test-id-2",
                aggregateId = "aggregate-456",
                eventType = "OrderUpdated",
                payload = "simple-payload",
                partition = 1,
                createdAt = now,
                status = OutboxRecordStatus.COMPLETED,
                completedAt = null,
                retryCount = 0,
                nextRetryAt = now,
            )

        val entity = OutboxRecordEntityMapper.map(record)

        assertThat(entity.id).isEqualTo("test-id-2")
        assertThat(entity.status).isEqualTo(OutboxRecordStatus.COMPLETED)
        assertThat(entity.aggregateId).isEqualTo("aggregate-456")
        assertThat(entity.eventType).isEqualTo("OrderUpdated")
        assertThat(entity.payload).isEqualTo("simple-payload")
        assertThat(entity.createdAt).isEqualTo(now)
        assertThat(entity.completedAt).isNull()
        assertThat(entity.retryCount).isEqualTo(0)
        assertThat(entity.nextRetryAt).isEqualTo(now)
    }

    @Test
    fun `should map OutboxRecord with FAILED status`() {
        val now = OffsetDateTime.now()
        val record =
            OutboxRecord.restore(
                id = UUID.randomUUID().toString(),
                aggregateId = "failed-aggregate",
                eventType = "FailedEvent",
                payload = "failed-payload",
                partition = 1,
                createdAt = now,
                status = OutboxRecordStatus.FAILED,
                completedAt = null,
                retryCount = 5,
                nextRetryAt = now,
            )

        val entity = OutboxRecordEntityMapper.map(record)

        assertThat(entity.status).isEqualTo(OutboxRecordStatus.FAILED)
        assertThat(entity.retryCount).isEqualTo(5)
        assertThat(entity.aggregateId).isEqualTo("failed-aggregate")
    }

    @Test
    fun `should map OutboxRecordEntity to OutboxRecord with all properties`() {
        val now = OffsetDateTime.now()
        val completedAt = now.plusMinutes(3)
        val nextRetryAt = now.plusMinutes(15)

        val entity =
            OutboxRecordEntity(
                id = "entity-id-1",
                status = OutboxRecordStatus.NEW,
                aggregateId = "entity-aggregate-789",
                eventType = "EntityEvent",
                payload = """{"entityId": "789", "data": "test"}""",
                partitionNo = 1,
                createdAt = now,
                completedAt = completedAt,
                retryCount = 2,
                nextRetryAt = nextRetryAt,
            )

        val record = OutboxRecordEntityMapper.map(entity)

        assertThat(record.id).isEqualTo("entity-id-1")
        assertThat(record.status).isEqualTo(OutboxRecordStatus.NEW)
        assertThat(record.aggregateId).isEqualTo("entity-aggregate-789")
        assertThat(record.eventType).isEqualTo("EntityEvent")
        assertThat(record.payload).isEqualTo("""{"entityId": "789", "data": "test"}""")
        assertThat(record.createdAt).isEqualTo(now)
        assertThat(record.completedAt).isEqualTo(completedAt)
        assertThat(record.retryCount).isEqualTo(2)
        assertThat(record.nextRetryAt).isEqualTo(nextRetryAt)
    }

    @Test
    fun `should map OutboxRecordEntity to OutboxRecord with null values`() {
        val now = OffsetDateTime.now()

        val entity =
            OutboxRecordEntity(
                id = "entity-id-2",
                status = OutboxRecordStatus.COMPLETED,
                aggregateId = "completed-aggregate",
                eventType = "CompletedEvent",
                payload = "completed-payload",
                partitionNo = 1,
                createdAt = now,
                completedAt = null,
                retryCount = 0,
                nextRetryAt = now,
            )

        val record = OutboxRecordEntityMapper.map(entity)

        assertThat(record.id).isEqualTo("entity-id-2")
        assertThat(record.status).isEqualTo(OutboxRecordStatus.COMPLETED)
        assertThat(record.aggregateId).isEqualTo("completed-aggregate")
        assertThat(record.eventType).isEqualTo("CompletedEvent")
        assertThat(record.payload).isEqualTo("completed-payload")
        assertThat(record.createdAt).isEqualTo(now)
        assertThat(record.completedAt).isNull()
        assertThat(record.retryCount).isEqualTo(0)
        assertThat(record.nextRetryAt).isEqualTo(now)
    }

    @Test
    fun `should map OutboxRecordEntity with FAILED status`() {
        val now = OffsetDateTime.now()

        val entity =
            OutboxRecordEntity(
                id = "failed-entity",
                status = OutboxRecordStatus.FAILED,
                aggregateId = "failed-entity-aggregate",
                eventType = "FailedEntityEvent",
                payload = "failed-entity-payload",
                partitionNo = 1,
                createdAt = OffsetDateTime.now(),
                completedAt = null,
                retryCount = 10,
                nextRetryAt = now,
            )

        val record = OutboxRecordEntityMapper.map(entity)

        assertThat(record.status).isEqualTo(OutboxRecordStatus.FAILED)
        assertThat(record.retryCount).isEqualTo(10)
        assertThat(record.aggregateId).isEqualTo("failed-entity-aggregate")
    }

    @Test
    fun `should maintain data integrity in round-trip mapping`() {
        val now = OffsetDateTime.now()
        val completedAt = now.plusMinutes(1)
        val nextRetryAt = now.plusMinutes(5)

        val originalRecord =
            OutboxRecord.restore(
                id = "round-trip-test",
                aggregateId = "round-trip-aggregate",
                eventType = "RoundTripEvent",
                payload = """{"test": "round-trip", "number": 42}""",
                partition = 1,
                createdAt = now,
                status = OutboxRecordStatus.NEW,
                completedAt = completedAt,
                retryCount = 1,
                nextRetryAt = nextRetryAt,
            )

        val entity = OutboxRecordEntityMapper.map(originalRecord)
        val mappedBackRecord = OutboxRecordEntityMapper.map(entity)

        assertThat(mappedBackRecord.id).isEqualTo(originalRecord.id)
        assertThat(mappedBackRecord.status).isEqualTo(originalRecord.status)
        assertThat(mappedBackRecord.aggregateId).isEqualTo(originalRecord.aggregateId)
        assertThat(mappedBackRecord.eventType).isEqualTo(originalRecord.eventType)
        assertThat(mappedBackRecord.payload).isEqualTo(originalRecord.payload)
        assertThat(mappedBackRecord.createdAt).isEqualTo(originalRecord.createdAt)
        assertThat(mappedBackRecord.completedAt).isEqualTo(originalRecord.completedAt)
        assertThat(mappedBackRecord.retryCount).isEqualTo(originalRecord.retryCount)
        assertThat(mappedBackRecord.nextRetryAt).isEqualTo(originalRecord.nextRetryAt)
    }

    @Test
    fun `should handle large payload mapping`() {
        val now = OffsetDateTime.now()
        val largePayload = "x".repeat(10000) // 10KB payload
        val record =
            OutboxRecord.restore(
                id = "large-payload-test",
                aggregateId = "large-aggregate",
                eventType = "LargeEvent",
                payload = largePayload,
                partition = 1,
                createdAt = now,
                status = OutboxRecordStatus.NEW,
                completedAt = null,
                retryCount = 0,
                nextRetryAt = now,
            )

        val entity = OutboxRecordEntityMapper.map(record)
        val mappedBack = OutboxRecordEntityMapper.map(entity)

        assertThat(entity.payload).hasSize(10000)
        assertThat(mappedBack.payload).isEqualTo(largePayload)
    }

    @Test
    fun `should map all status types correctly`() {
        // Test all enum values
        for (status in OutboxRecordStatus.entries) {
            val now = OffsetDateTime.now()
            val record =
                OutboxRecord.restore(
                    id = "status-test-${status.name}",
                    aggregateId = "status-aggregate",
                    eventType = "StatusEvent",
                    payload = "status-payload",
                    partition = 1,
                    createdAt = now,
                    status = status,
                    completedAt = null,
                    retryCount = 0,
                    nextRetryAt = now,
                )

            val entity = OutboxRecordEntityMapper.map(record)
            val mappedBack = OutboxRecordEntityMapper.map(entity)

            assertThat(entity.status).isEqualTo(status)
            assertThat(mappedBack.status).isEqualTo(status)
        }
    }
}
