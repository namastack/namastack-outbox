package io.namastack.outbox

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

class OutboxRecordTest {
    private val clock = Clock.fixed(Instant.parse("2025-09-25T10:00:00Z"), ZoneOffset.UTC)
    private val now = OffsetDateTime.now(clock)

    @Test
    fun `markCompleted should set status to COMPLETED and completedAt timestamp when record is NEW`() {
        val record =
            OutboxRecord.restore(
                id = "test-id",
                aggregateId = "test-aggregate",
                eventType = "TestEvent",
                payload = "test-payload",
                partition = 1,
                createdAt = now.minusMinutes(10),
                status = OutboxRecordStatus.NEW,
                completedAt = null,
                retryCount = 0,
                nextRetryAt = now,
            )

        record.markCompleted(clock)

        assertThat(record.status).isEqualTo(OutboxRecordStatus.COMPLETED)
        assertThat(record.completedAt).isEqualTo(now)
    }

    @Test
    fun `markCompleted should not modify record when already completed`() {
        val completedAt = now.minusMinutes(5)
        val record =
            OutboxRecord.restore(
                id = "test-id",
                aggregateId = "test-aggregate",
                eventType = "TestEvent",
                payload = "test-payload",
                partition = 1,
                createdAt = now.minusMinutes(10),
                status = OutboxRecordStatus.COMPLETED,
                completedAt = completedAt,
                retryCount = 0,
                nextRetryAt = now,
            )

        record.markCompleted(clock)

        assertThat(record.status).isEqualTo(OutboxRecordStatus.COMPLETED)
        assertThat(record.completedAt).isEqualTo(completedAt)
    }

    @Test
    fun `markFailed should set status to FAILED when record is NEW`() {
        val record =
            OutboxRecord.restore(
                id = "test-id",
                aggregateId = "test-aggregate",
                eventType = "TestEvent",
                payload = "test-payload",
                partition = 1,
                createdAt = now.minusMinutes(10),
                status = OutboxRecordStatus.NEW,
                completedAt = null,
                retryCount = 0,
                nextRetryAt = now.plusMinutes(5),
            )

        record.markFailed()

        assertThat(record.status).isEqualTo(OutboxRecordStatus.FAILED)
    }

    @Test
    fun `markFailed should not modify record when already failed`() {
        val record =
            OutboxRecord.restore(
                id = "test-id",
                aggregateId = "test-aggregate",
                eventType = "TestEvent",
                payload = "test-payload",
                partition = 1,
                createdAt = now.minusMinutes(10),
                status = OutboxRecordStatus.FAILED,
                completedAt = null,
                retryCount = 2,
                nextRetryAt = now.plusMinutes(5),
            )

        record.markFailed()

        assertThat(record.status).isEqualTo(OutboxRecordStatus.FAILED)
    }

    @Test
    fun `markFailed should change status from COMPLETED to FAILED`() {
        val record =
            OutboxRecord.restore(
                id = "test-id",
                aggregateId = "test-aggregate",
                eventType = "TestEvent",
                payload = "test-payload",
                partition = 1,
                createdAt = now.minusMinutes(10),
                status = OutboxRecordStatus.COMPLETED,
                completedAt = now.minusMinutes(5),
                retryCount = 0,
                nextRetryAt = now.plusMinutes(5),
            )

        record.markFailed()

        assertThat(record.status).isEqualTo(OutboxRecordStatus.FAILED)
    }

    @Test
    fun `incrementRetryCount should increase retry count by one`() {
        val record =
            OutboxRecord.restore(
                id = "test-id",
                aggregateId = "test-aggregate",
                eventType = "TestEvent",
                payload = "test-payload",
                partition = 1,
                createdAt = now.minusMinutes(10),
                status = OutboxRecordStatus.NEW,
                completedAt = null,
                retryCount = 2,
                nextRetryAt = now,
            )

        record.incrementRetryCount()

        assertThat(record.retryCount).isEqualTo(3)
    }

    @Test
    fun `incrementRetryCount should work from zero`() {
        val record =
            OutboxRecord.restore(
                id = "test-id",
                aggregateId = "test-aggregate",
                eventType = "TestEvent",
                payload = "test-payload",
                partition = 1,
                createdAt = now.minusMinutes(10),
                status = OutboxRecordStatus.NEW,
                completedAt = null,
                retryCount = 0,
                nextRetryAt = now,
            )

        record.incrementRetryCount()

        assertThat(record.retryCount).isEqualTo(1)
    }

    @Test
    fun `canBeRetried should return true when nextRetryAt is in the past and status is NEW`() {
        val record =
            OutboxRecord.restore(
                id = "test-id",
                aggregateId = "test-aggregate",
                eventType = "TestEvent",
                payload = "test-payload",
                partition = 1,
                createdAt = now.minusMinutes(10),
                status = OutboxRecordStatus.NEW,
                completedAt = null,
                retryCount = 0,
                nextRetryAt = now.minusMinutes(1), // in the past
            )

        val result = record.canBeRetried(clock)

        assertThat(result).isTrue()
    }

    @Test
    fun `canBeRetried should return false when nextRetryAt is in the future`() {
        val record =
            OutboxRecord.restore(
                id = "test-id",
                aggregateId = "test-aggregate",
                eventType = "TestEvent",
                payload = "test-payload",
                partition = 1,
                createdAt = now.minusMinutes(10),
                status = OutboxRecordStatus.NEW,
                completedAt = null,
                retryCount = 0,
                nextRetryAt = now.plusMinutes(1), // in the future
            )

        val result = record.canBeRetried(clock)

        assertThat(result).isFalse()
    }

    @Test
    fun `canBeRetried should return false when status is not NEW`() {
        val record =
            OutboxRecord.restore(
                id = "test-id",
                aggregateId = "test-aggregate",
                eventType = "TestEvent",
                payload = "test-payload",
                partition = 1,
                createdAt = now.minusMinutes(10),
                status = OutboxRecordStatus.COMPLETED,
                completedAt = now.minusMinutes(5),
                retryCount = 0,
                nextRetryAt = now.minusMinutes(1), // in the past
            )

        val result = record.canBeRetried(clock)

        assertThat(result).isFalse()
    }

    @Test
    fun `canBeRetried should return false when nextRetryAt equals current time`() {
        val record =
            OutboxRecord.restore(
                id = "test-id",
                aggregateId = "test-aggregate",
                eventType = "TestEvent",
                payload = "test-payload",
                partition = 1,
                createdAt = now.minusMinutes(10),
                status = OutboxRecordStatus.NEW,
                completedAt = null,
                retryCount = 0,
                nextRetryAt = now, // exactly now
            )

        val result = record.canBeRetried(clock)

        assertThat(result).isFalse() // isBefore returns false for equal times
    }

    @Test
    fun `retriesExhausted should return true when retry count equals max retries`() {
        val record =
            OutboxRecord.restore(
                id = "test-id",
                aggregateId = "test-aggregate",
                eventType = "TestEvent",
                payload = "test-payload",
                partition = 1,
                createdAt = now.minusMinutes(10),
                status = OutboxRecordStatus.NEW,
                completedAt = null,
                retryCount = 3,
                nextRetryAt = now,
            )

        val result = record.retriesExhausted(3)

        assertThat(result).isTrue()
    }

    @Test
    fun `retriesExhausted should return true when retry count exceeds max retries`() {
        val record =
            OutboxRecord.restore(
                id = "test-id",
                aggregateId = "test-aggregate",
                eventType = "TestEvent",
                payload = "test-payload",
                partition = 1,
                createdAt = now.minusMinutes(10),
                status = OutboxRecordStatus.NEW,
                completedAt = null,
                retryCount = 5,
                nextRetryAt = now,
            )

        val result = record.retriesExhausted(3)

        assertThat(result).isTrue()
    }

    @Test
    fun `retriesExhausted should return false when retry count is less than max retries`() {
        val record =
            OutboxRecord.restore(
                id = "test-id",
                aggregateId = "test-aggregate",
                eventType = "TestEvent",
                payload = "test-payload",
                partition = 1,
                createdAt = now.minusMinutes(10),
                status = OutboxRecordStatus.NEW,
                completedAt = null,
                retryCount = 2,
                nextRetryAt = now,
            )

        val result = record.retriesExhausted(3)

        assertThat(result).isFalse()
    }

    @Test
    fun `scheduleNextRetry should set nextRetryAt`() {
        val record =
            OutboxRecord.restore(
                id = "test-id",
                aggregateId = "test-aggregate",
                eventType = "TestEvent",
                payload = "test-payload",
                partition = 1,
                createdAt = now.minusMinutes(10),
                status = OutboxRecordStatus.NEW,
                completedAt = null,
                retryCount = 0,
                nextRetryAt = now,
            )

        val delay = Duration.of(5, ChronoUnit.SECONDS)

        record.scheduleNextRetry(delay, clock)

        assertThat(record.nextRetryAt).isEqualTo(now.plus(delay))
    }

    @Test
    fun `restore should create record with all properties`() {
        val record =
            OutboxRecord.restore(
                id = "custom-id",
                aggregateId = "test-aggregate",
                eventType = "TestEvent",
                payload = "test-payload",
                partition = 1,
                createdAt = now.minusHours(1),
                status = OutboxRecordStatus.COMPLETED,
                completedAt = now.minusMinutes(30),
                retryCount = 2,
                nextRetryAt = now.plusMinutes(10),
            )

        assertThat(record.id).isEqualTo("custom-id")
        assertThat(record.status).isEqualTo(OutboxRecordStatus.COMPLETED)
        assertThat(record.aggregateId).isEqualTo("test-aggregate")
        assertThat(record.eventType).isEqualTo("TestEvent")
        assertThat(record.payload).isEqualTo("test-payload")
        assertThat(record.createdAt).isEqualTo(now.minusHours(1))
        assertThat(record.completedAt).isEqualTo(now.minusMinutes(30))
        assertThat(record.retryCount).isEqualTo(2)
        assertThat(record.nextRetryAt).isEqualTo(now.plusMinutes(10))
    }

    @Test
    fun `builder should create record with default values`() {
        val record =
            OutboxRecord
                .builder()
                .aggregateId("test-aggregate")
                .eventType("TestEvent")
                .payload("test-payload")
                .build(clock)

        assertThat(record.id).isNotEmpty()
        assertThat(record.status).isEqualTo(OutboxRecordStatus.NEW)
        assertThat(record.aggregateId).isEqualTo("test-aggregate")
        assertThat(record.eventType).isEqualTo("TestEvent")
        assertThat(record.payload).isEqualTo("test-payload")
        assertThat(record.partition).isNotNull()
        assertThat(record.createdAt).isNotNull()
        assertThat(record.completedAt).isNull()
        assertThat(record.retryCount).isEqualTo(0)
        assertThat(record.nextRetryAt).isNotNull()
    }
}
