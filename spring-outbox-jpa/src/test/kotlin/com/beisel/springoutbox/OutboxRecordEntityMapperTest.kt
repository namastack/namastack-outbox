package com.beisel.springoutbox

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

class OutboxRecordEntityMapperTest {
    @Test
    fun `should map OutboxRecord to OutboxRecordEntity with all properties`() {
        // given
        val now = OffsetDateTime.now()
        val completedAt = now.plusMinutes(5)
        val nextRetryAt = now.plusMinutes(10)

        val record =
            OutboxRecord
                .Builder()
                .id("test-id")
                .status(OutboxRecordStatus.NEW)
                .aggregateId("aggregate-123")
                .eventType("OrderCreated")
                .payload("""{"orderId": "123", "amount": 100.50}""")
                .createdAt(now)
                .completedAt(completedAt)
                .retryCount(3)
                .nextRetryAt(nextRetryAt)
                .build()

        // when
        val entity = OutboxRecordEntityMapper.map(record)

        // then
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
        // given
        val now = OffsetDateTime.now()

        val record =
            OutboxRecord
                .Builder()
                .id("test-id-2")
                .status(OutboxRecordStatus.COMPLETED)
                .aggregateId("aggregate-456")
                .eventType("OrderUpdated")
                .payload("simple-payload")
                .createdAt(now)
                .completedAt(null)
                .retryCount(0)
                .nextRetryAt(null)
                .build()

        // when
        val entity = OutboxRecordEntityMapper.map(record)

        // then
        assertThat(entity.id).isEqualTo("test-id-2")
        assertThat(entity.status).isEqualTo(OutboxRecordStatus.COMPLETED)
        assertThat(entity.aggregateId).isEqualTo("aggregate-456")
        assertThat(entity.eventType).isEqualTo("OrderUpdated")
        assertThat(entity.payload).isEqualTo("simple-payload")
        assertThat(entity.createdAt).isEqualTo(now)
        assertThat(entity.completedAt).isNull()
        assertThat(entity.retryCount).isEqualTo(0)
        assertThat(entity.nextRetryAt).isNull()
    }

    @Test
    fun `should map OutboxRecord with FAILED status`() {
        // given
        val record =
            OutboxRecord
                .Builder()
                .id(UUID.randomUUID().toString())
                .status(OutboxRecordStatus.FAILED)
                .aggregateId("failed-aggregate")
                .eventType("FailedEvent")
                .payload("failed-payload")
                .retryCount(5)
                .build()

        // when
        val entity = OutboxRecordEntityMapper.map(record)

        // then
        assertThat(entity.status).isEqualTo(OutboxRecordStatus.FAILED)
        assertThat(entity.retryCount).isEqualTo(5)
        assertThat(entity.aggregateId).isEqualTo("failed-aggregate")
    }

    @Test
    fun `should map OutboxRecordEntity to OutboxRecord with all properties`() {
        // given
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
                createdAt = now,
                completedAt = completedAt,
                retryCount = 2,
                nextRetryAt = nextRetryAt,
            )

        // when
        val record = OutboxRecordEntityMapper.map(entity)

        // then
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
        // given
        val now = OffsetDateTime.now()

        val entity =
            OutboxRecordEntity(
                id = "entity-id-2",
                status = OutboxRecordStatus.COMPLETED,
                aggregateId = "completed-aggregate",
                eventType = "CompletedEvent",
                payload = "completed-payload",
                createdAt = now,
                completedAt = null,
                retryCount = 0,
                nextRetryAt = null,
            )

        // when
        val record = OutboxRecordEntityMapper.map(entity)

        // then
        assertThat(record.id).isEqualTo("entity-id-2")
        assertThat(record.status).isEqualTo(OutboxRecordStatus.COMPLETED)
        assertThat(record.aggregateId).isEqualTo("completed-aggregate")
        assertThat(record.eventType).isEqualTo("CompletedEvent")
        assertThat(record.payload).isEqualTo("completed-payload")
        assertThat(record.createdAt).isEqualTo(now)
        assertThat(record.completedAt).isNull()
        assertThat(record.retryCount).isEqualTo(0)
        assertThat(record.nextRetryAt).isNull()
    }

    @Test
    fun `should map OutboxRecordEntity with FAILED status`() {
        // given
        val entity =
            OutboxRecordEntity(
                id = "failed-entity",
                status = OutboxRecordStatus.FAILED,
                aggregateId = "failed-entity-aggregate",
                eventType = "FailedEntityEvent",
                payload = "failed-entity-payload",
                createdAt = OffsetDateTime.now(),
                completedAt = null,
                retryCount = 10,
                nextRetryAt = null,
            )

        // when
        val record = OutboxRecordEntityMapper.map(entity)

        // then
        assertThat(record.status).isEqualTo(OutboxRecordStatus.FAILED)
        assertThat(record.retryCount).isEqualTo(10)
        assertThat(record.nextRetryAt).isNull()
        assertThat(record.aggregateId).isEqualTo("failed-entity-aggregate")
    }

    @Test
    fun `should maintain data integrity in round-trip mapping`() {
        // given - original record
        val originalRecord =
            OutboxRecord
                .Builder()
                .id("round-trip-test")
                .status(OutboxRecordStatus.NEW)
                .aggregateId("round-trip-aggregate")
                .eventType("RoundTripEvent")
                .payload("""{"test": "round-trip", "number": 42}""")
                .createdAt(OffsetDateTime.now())
                .completedAt(OffsetDateTime.now().plusMinutes(1))
                .retryCount(1)
                .nextRetryAt(OffsetDateTime.now().plusMinutes(5))
                .build()

        // when - round trip: Record -> Entity -> Record
        val entity = OutboxRecordEntityMapper.map(originalRecord)
        val mappedBackRecord = OutboxRecordEntityMapper.map(entity)

        // then - all properties should be identical
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
        // given
        val largePayload = "x".repeat(10000) // 10KB payload
        val record =
            OutboxRecord
                .Builder()
                .id("large-payload-test")
                .status(OutboxRecordStatus.NEW)
                .aggregateId("large-aggregate")
                .eventType("LargeEvent")
                .payload(largePayload)
                .build()

        // when
        val entity = OutboxRecordEntityMapper.map(record)
        val mappedBack = OutboxRecordEntityMapper.map(entity)

        // then
        assertThat(entity.payload).hasSize(10000)
        assertThat(mappedBack.payload).isEqualTo(largePayload)
    }

    @Test
    fun `should map all status types correctly`() {
        // Test all enum values
        for (status in OutboxRecordStatus.entries) {
            // given
            val record =
                OutboxRecord
                    .Builder()
                    .id("status-test-${status.name}")
                    .status(status)
                    .aggregateId("status-aggregate")
                    .eventType("StatusEvent")
                    .payload("status-payload")
                    .build()

            // when
            val entity = OutboxRecordEntityMapper.map(record)
            val mappedBack = OutboxRecordEntityMapper.map(entity)

            // then
            assertThat(entity.status).isEqualTo(status)
            assertThat(mappedBack.status).isEqualTo(status)
        }
    }
}
