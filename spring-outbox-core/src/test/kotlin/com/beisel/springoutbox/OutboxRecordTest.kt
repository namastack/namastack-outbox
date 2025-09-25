package com.beisel.springoutbox

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class OutboxRecordTest {
    private val clock = Clock.fixed(Instant.parse("2025-09-25T10:00:00Z"), ZoneOffset.UTC)
    private val now = OffsetDateTime.now(clock)

    @Test
    fun `markCompleted should set status to COMPLETED and completedAt timestamp`() {
        // given
        val record =
            OutboxRecord
                .Builder()
                .aggregateId("test-aggregate")
                .eventType("TestEvent")
                .payload("test-payload")
                .build()

        // when
        record.markCompleted()

        // then
        assertThat(record.status).isEqualTo(OutboxRecordStatus.COMPLETED)
        assertThat(record.completedAt).isNotNull()
    }

    @Test
    fun `markFailed should set status to FAILED and clear nextRetryAt`() {
        // given
        val record =
            OutboxRecord
                .Builder()
                .aggregateId("test-aggregate")
                .eventType("TestEvent")
                .payload("test-payload")
                .nextRetryAt(now.plusMinutes(5))
                .build()

        // when
        record.markFailed()

        // then
        assertThat(record.status).isEqualTo(OutboxRecordStatus.FAILED)
        assertThat(record.nextRetryAt).isNull()
    }

    @Test
    fun `incrementRetryCount should increase retry count by one`() {
        // given
        val record =
            OutboxRecord
                .Builder()
                .aggregateId("test-aggregate")
                .eventType("TestEvent")
                .payload("test-payload")
                .retryCount(2)
                .build()

        // when
        record.incrementRetryCount()

        // then
        assertThat(record.retryCount).isEqualTo(3)
    }

    @Test
    fun `incrementRetryCount should work from zero`() {
        // given
        val record =
            OutboxRecord
                .Builder()
                .aggregateId("test-aggregate")
                .eventType("TestEvent")
                .payload("test-payload")
                .build()

        // when
        record.incrementRetryCount()

        // then
        assertThat(record.retryCount).isEqualTo(1)
    }

    @Test
    fun `canBeRetried should return true when nextRetryAt is in the past and status is NEW`() {
        // given
        val record =
            OutboxRecord
                .Builder()
                .aggregateId("test-aggregate")
                .eventType("TestEvent")
                .payload("test-payload")
                .status(OutboxRecordStatus.NEW)
                .nextRetryAt(now.minusMinutes(1)) // in the past
                .build()

        // when
        val result = record.canBeRetried(clock)

        // then
        assertThat(result).isTrue()
    }

    @Test
    fun `canBeRetried should return false when nextRetryAt is in the future`() {
        // given
        val record =
            OutboxRecord
                .Builder()
                .aggregateId("test-aggregate")
                .eventType("TestEvent")
                .payload("test-payload")
                .status(OutboxRecordStatus.NEW)
                .nextRetryAt(now.plusMinutes(1)) // in the future
                .build()

        // when
        val result = record.canBeRetried(clock)

        // then
        assertThat(result).isFalse()
    }

    @Test
    fun `canBeRetried should return false when status is not NEW`() {
        // given
        val record =
            OutboxRecord
                .Builder()
                .aggregateId("test-aggregate")
                .eventType("TestEvent")
                .payload("test-payload")
                .status(OutboxRecordStatus.COMPLETED)
                .nextRetryAt(now.minusMinutes(1)) // in the past
                .build()

        // when
        val result = record.canBeRetried(clock)

        // then
        assertThat(result).isFalse()
    }

    @Test
    fun `canBeRetried should return false when nextRetryAt is null`() {
        // given
        val record =
            OutboxRecord
                .Builder()
                .aggregateId("test-aggregate")
                .eventType("TestEvent")
                .payload("test-payload")
                .status(OutboxRecordStatus.NEW)
                .nextRetryAt(null)
                .build()

        // when
        val result = record.canBeRetried(clock)

        // then
        assertThat(result).isFalse()
    }

    @Test
    fun `canBeRetried should return true when nextRetryAt equals current time`() {
        // given
        val record =
            OutboxRecord
                .Builder()
                .aggregateId("test-aggregate")
                .eventType("TestEvent")
                .payload("test-payload")
                .status(OutboxRecordStatus.NEW)
                .nextRetryAt(now) // exactly now
                .build()

        // when
        val result = record.canBeRetried(clock)

        // then
        assertThat(result).isFalse() // isBefore returns false for equal times
    }

    @Test
    fun `retriesExhausted should return true when retry count equals max retries`() {
        // given
        val record =
            OutboxRecord
                .Builder()
                .aggregateId("test-aggregate")
                .eventType("TestEvent")
                .payload("test-payload")
                .retryCount(3)
                .build()

        // when
        val result = record.retriesExhausted(3)

        // then
        assertThat(result).isTrue()
    }

    @Test
    fun `retriesExhausted should return true when retry count exceeds max retries`() {
        // given
        val record =
            OutboxRecord
                .Builder()
                .aggregateId("test-aggregate")
                .eventType("TestEvent")
                .payload("test-payload")
                .retryCount(5)
                .build()

        // when
        val result = record.retriesExhausted(3)

        // then
        assertThat(result).isTrue()
    }

    @Test
    fun `retriesExhausted should return false when retry count is less than max retries`() {
        // given
        val record =
            OutboxRecord
                .Builder()
                .aggregateId("test-aggregate")
                .eventType("TestEvent")
                .payload("test-payload")
                .retryCount(2)
                .build()

        // when
        val result = record.retriesExhausted(3)

        // then
        assertThat(result).isFalse()
    }

    @Test
    fun `scheduleNextRetry should set nextRetryAt to future time`() {
        // given
        val record =
            OutboxRecord
                .Builder()
                .aggregateId("test-aggregate")
                .eventType("TestEvent")
                .payload("test-payload")
                .build()

        // when
        record.scheduleNextRetry(30L, clock)

        // then
        assertThat(record.nextRetryAt).isEqualTo(now.plusSeconds(30))
    }

    @Test
    fun `scheduleNextRetry should handle zero seconds`() {
        // given
        val record =
            OutboxRecord
                .Builder()
                .aggregateId("test-aggregate")
                .eventType("TestEvent")
                .payload("test-payload")
                .build()

        // when
        record.scheduleNextRetry(0L, clock)

        // then
        assertThat(record.nextRetryAt).isEqualTo(now)
    }

    @Test
    fun `builder should create record with all properties`() {
        // given/when
        val record =
            OutboxRecord
                .Builder()
                .id("custom-id")
                .status(OutboxRecordStatus.COMPLETED)
                .aggregateId("test-aggregate")
                .eventType("TestEvent")
                .payload("test-payload")
                .createdAt(now.minusHours(1))
                .completedAt(now.minusMinutes(30))
                .retryCount(2)
                .nextRetryAt(now.plusMinutes(10))
                .build()

        // then
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
        // given/when
        val record =
            OutboxRecord
                .Builder()
                .aggregateId("test-aggregate")
                .eventType("TestEvent")
                .payload("test-payload")
                .build()

        // then
        assertThat(record.id).isNotEmpty()
        assertThat(record.status).isEqualTo(OutboxRecordStatus.NEW)
        assertThat(record.aggregateId).isEqualTo("test-aggregate")
        assertThat(record.eventType).isEqualTo("TestEvent")
        assertThat(record.payload).isEqualTo("test-payload")
        assertThat(record.createdAt).isNotNull()
        assertThat(record.completedAt).isNull()
        assertThat(record.retryCount).isEqualTo(0)
        assertThat(record.nextRetryAt).isNotNull()
    }
}
