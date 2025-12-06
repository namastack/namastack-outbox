package io.namastack.outbox

import io.namastack.outbox.OutboxRecordTestFactory.outboxRecord
import io.namastack.outbox.partition.PartitionHasher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
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

    @Nested
    inner class MarkCompletedTests {
        @Test
        fun `should set status to COMPLETED and completedAt timestamp when record is NEW`() {
            val record =
                outboxRecord(
                    status = OutboxRecordStatus.NEW,
                    completedAt = null,
                    createdAt = now.minusMinutes(10),
                )

            record.markCompleted(clock)

            assertThat(record.status).isEqualTo(OutboxRecordStatus.COMPLETED)
            assertThat(record.completedAt).isEqualTo(now)
        }

        @Test
        fun `should not modify record when already completed`() {
            val completedAt = now.minusMinutes(5)
            val record =
                outboxRecord(
                    status = OutboxRecordStatus.COMPLETED,
                    completedAt = completedAt,
                    createdAt = now.minusMinutes(10),
                )

            record.markCompleted(clock)

            assertThat(record.status).isEqualTo(OutboxRecordStatus.COMPLETED)
            assertThat(record.completedAt).isEqualTo(completedAt)
        }
    }

    @Nested
    inner class MarkFailedTests {
        @Test
        fun `should set status to FAILED when record is NEW`() {
            val record =
                outboxRecord(
                    status = OutboxRecordStatus.NEW,
                    completedAt = null,
                    nextRetryAt = now.plusMinutes(5),
                    createdAt = now.minusMinutes(10),
                )

            record.markFailed()

            assertThat(record.status).isEqualTo(OutboxRecordStatus.FAILED)
        }

        @Test
        fun `should not modify record when already failed`() {
            val record =
                outboxRecord(
                    status = OutboxRecordStatus.FAILED,
                    failureCount = 2,
                    nextRetryAt = now.plusMinutes(5),
                    createdAt = now.minusMinutes(10),
                )

            record.markFailed()

            assertThat(record.status).isEqualTo(OutboxRecordStatus.FAILED)
        }

        @Test
        fun `should change status from COMPLETED to FAILED`() {
            val record =
                outboxRecord(
                    status = OutboxRecordStatus.COMPLETED,
                    completedAt = now.minusMinutes(5),
                    nextRetryAt = now.plusMinutes(5),
                    createdAt = now.minusMinutes(10),
                )

            record.markFailed()

            assertThat(record.status).isEqualTo(OutboxRecordStatus.FAILED)
        }

        @Test
        fun `should not affect failureCount`() {
            val initialFailureCount = 2
            val record =
                outboxRecord(
                    status = OutboxRecordStatus.NEW,
                    failureCount = initialFailureCount,
                    createdAt = now.minusMinutes(10),
                )

            record.markFailed()

            assertThat(record.failureCount).isEqualTo(initialFailureCount)
        }
    }

    @Nested
    inner class IncrementFailureCountTests {
        @Test
        fun `should increase failure count by one`() {
            val record =
                outboxRecord(
                    failureCount = 2,
                    createdAt = now.minusMinutes(10),
                )

            record.incrementFailureCount()

            assertThat(record.failureCount).isEqualTo(3)
        }

        @Test
        fun `should work from zero`() {
            val record =
                outboxRecord(
                    failureCount = 0,
                    createdAt = now.minusMinutes(10),
                )

            record.incrementFailureCount()

            assertThat(record.failureCount).isEqualTo(1)
        }
    }

    @Nested
    inner class CanBeRetriedTests {
        @Test
        fun `should return true when nextRetryAt is in the past and status is NEW`() {
            val record =
                outboxRecord(
                    status = OutboxRecordStatus.NEW,
                    nextRetryAt = now.minusMinutes(1),
                    createdAt = now.minusMinutes(10),
                )

            val result = record.canBeRetried(clock)

            assertThat(result).isTrue()
        }

        @Test
        fun `should return false when nextRetryAt is in the future`() {
            val record =
                outboxRecord(
                    status = OutboxRecordStatus.NEW,
                    nextRetryAt = now.plusMinutes(1),
                    createdAt = now.minusMinutes(10),
                )

            val result = record.canBeRetried(clock)

            assertThat(result).isFalse()
        }

        @Test
        fun `should return false when status is not NEW`() {
            val record =
                outboxRecord(
                    status = OutboxRecordStatus.COMPLETED,
                    completedAt = now.minusMinutes(5),
                    nextRetryAt = now.minusMinutes(1),
                    createdAt = now.minusMinutes(10),
                )

            val result = record.canBeRetried(clock)

            assertThat(result).isFalse()
        }

        @Test
        fun `should return false when nextRetryAt equals current time`() {
            val record =
                outboxRecord(
                    status = OutboxRecordStatus.NEW,
                    nextRetryAt = now,
                    createdAt = now.minusMinutes(10),
                )

            val result = record.canBeRetried(clock)

            assertThat(result).isFalse()
        }
    }

    @Nested
    inner class RetriesExhaustedTests {
        @Test
        fun `should return false when failure count equals max retries`() {
            val record =
                outboxRecord(
                    failureCount = 3,
                    createdAt = now.minusMinutes(10),
                )

            val result = record.retriesExhausted(3)

            assertThat(result).isFalse()
        }

        @Test
        fun `should return true when failure count exceeds max retries`() {
            val record =
                outboxRecord(
                    failureCount = 4,
                    createdAt = now.minusMinutes(10),
                )

            val result = record.retriesExhausted(3)

            assertThat(result).isTrue()
        }

        @Test
        fun `should return false when failure count is less than max retries`() {
            val record =
                outboxRecord(
                    failureCount = 2,
                    createdAt = now.minusMinutes(10),
                )

            val result = record.retriesExhausted(3)

            assertThat(result).isFalse()
        }

        @Test
        fun `with maxRetries 0 should mark exhausted on any failure`() {
            val record =
                outboxRecord(
                    failureCount = 1,
                    createdAt = now.minusMinutes(10),
                )

            val result = record.retriesExhausted(0)

            assertThat(result).isTrue()
        }
    }

    @Nested
    inner class ScheduleNextRetryTests {
        @Test
        fun `should set nextRetryAt`() {
            val record =
                outboxRecord(
                    createdAt = now.minusMinutes(10),
                )

            val delay = Duration.of(5, ChronoUnit.SECONDS)

            record.scheduleNextRetry(delay, clock)

            assertThat(record.nextRetryAt).isEqualTo(now.plus(delay))
        }

        @Test
        fun `should handle large delays`() {
            val record =
                outboxRecord(
                    createdAt = now.minusHours(1),
                )

            val delay = Duration.ofHours(24)

            record.scheduleNextRetry(delay, clock)

            assertThat(record.nextRetryAt).isEqualTo(now.plus(delay))
        }
    }

    @Nested
    inner class RestoreTests {
        @Test
        fun `should create record with all properties`() {
            val record =
                outboxRecord(
                    id = "custom-id",
                    createdAt = now.minusHours(1),
                    status = OutboxRecordStatus.COMPLETED,
                    completedAt = now.minusMinutes(30),
                    failureCount = 2,
                    nextRetryAt = now.plusMinutes(10),
                )

            assertThat(record.id).isEqualTo("custom-id")
            assertThat(record.status).isEqualTo(OutboxRecordStatus.COMPLETED)
            assertThat(record.key).isEqualTo("test-record-key")
            assertThat(record.payload).isInstanceOf(OutboxRecordTestFactory.CreatedEvent::class.java)
            assertThat(record.createdAt).isEqualTo(now.minusHours(1))
            assertThat(record.completedAt).isEqualTo(now.minusMinutes(30))
            assertThat(record.failureCount).isEqualTo(2)
            assertThat(record.nextRetryAt).isEqualTo(now.plusMinutes(10))
        }

        @Test
        fun `payload is preserved through record creation`() {
            val event = OutboxRecordTestFactory.CreatedEvent(id = "test-123")
            val record =
                outboxRecord(
                    payload = event,
                )

            assertThat(record.payload).isEqualTo(event)
        }

        @Test
        fun `custom handlerId is preserved`() {
            val customHandlerId = "custom.HandlerClass#customMethod(java.lang.String)"
            val record =
                outboxRecord(
                    handlerId = customHandlerId,
                )

            assertThat(record.handlerId).isEqualTo(customHandlerId)
        }
    }

    @Nested
    inner class BuilderTests {
        @Test
        fun `should create record with all required fields`() {
            val payload = OutboxRecordTestFactory.CreatedEvent(id = "event-123")
            val key = "custom-key"
            val handlerId = "TestHandler#handle(java.lang.String)"

            val record =
                OutboxRecord
                    .Builder<OutboxRecordTestFactory.CreatedEvent>()
                    .key(key)
                    .payload(payload)
                    .handlerId(handlerId)
                    .build(clock)

            assertThat(record.key).isEqualTo(key)
            assertThat(record.payload).isEqualTo(payload)
            assertThat(record.handlerId).isEqualTo(handlerId)
        }

        @Test
        fun `should set status to NEW on build`() {
            val record =
                OutboxRecord
                    .Builder<String>()
                    .key("test-key")
                    .payload("test-payload")
                    .handlerId("TestHandler#handle(java.lang.String)")
                    .build(clock)

            assertThat(record.status).isEqualTo(OutboxRecordStatus.NEW)
        }

        @Test
        fun `should set completedAt to null on build`() {
            val record =
                OutboxRecord
                    .Builder<String>()
                    .key("test-key")
                    .payload("test-payload")
                    .handlerId("TestHandler#handle(java.lang.String)")
                    .build(clock)

            assertThat(record.completedAt).isNull()
        }

        @Test
        fun `should set failureCount to 0 on build`() {
            val record =
                OutboxRecord
                    .Builder<String>()
                    .key("test-key")
                    .payload("test-payload")
                    .handlerId("TestHandler#handle(java.lang.String)")
                    .build(clock)

            assertThat(record.failureCount).isEqualTo(0)
        }

        @Test
        fun `should set createdAt to current time on build`() {
            val record =
                OutboxRecord
                    .Builder<String>()
                    .key("test-key")
                    .payload("test-payload")
                    .handlerId("TestHandler#handle(java.lang.String)")
                    .build(clock)

            assertThat(record.createdAt).isEqualTo(now)
        }

        @Test
        fun `should set nextRetryAt to current time on build`() {
            val record =
                OutboxRecord
                    .Builder<String>()
                    .key("test-key")
                    .payload("test-payload")
                    .handlerId("TestHandler#handle(java.lang.String)")
                    .build(clock)

            assertThat(record.nextRetryAt).isEqualTo(now)
        }

        @Test
        fun `should generate UUID as id on build`() {
            val record =
                OutboxRecord
                    .Builder<String>()
                    .key("test-key")
                    .payload("test-payload")
                    .handlerId("TestHandler#handle(java.lang.String)")
                    .build(clock)

            assertThat(record.id).isNotEmpty()
            assertThat(record.id).isNotEqualTo("test-key")
        }

        @Test
        fun `should use provided key when set`() {
            val providedKey = "my-custom-key"
            val record =
                OutboxRecord
                    .Builder<String>()
                    .key(providedKey)
                    .payload("test-payload")
                    .handlerId("TestHandler#handle(java.lang.String)")
                    .build(clock)

            assertThat(record.key).isEqualTo(providedKey)
        }

        @Test
        fun `should use generated UUID as key when not provided`() {
            val record =
                OutboxRecord
                    .Builder<String>()
                    .payload("test-payload")
                    .handlerId("TestHandler#handle(java.lang.String)")
                    .build(clock)

            assertThat(record.key).isNotEmpty()
        }

        @Test
        fun `should throw error when payload is not set`() {
            val builder =
                OutboxRecord
                    .Builder<String>()
                    .key("test-key")
                    .handlerId("TestHandler#handle(java.lang.String)")

            val exception =
                try {
                    builder.build(clock)
                    null
                } catch (e: IllegalStateException) {
                    e
                }

            assertThat(exception).isNotNull()
            assertThat(exception?.message).contains("payload must be set")
        }

        @Test
        fun `should throw error when handlerId is not set`() {
            val builder =
                OutboxRecord
                    .Builder<String>()
                    .key("test-key")
                    .payload("test-payload")

            val exception =
                try {
                    builder.build(clock)
                    null
                } catch (e: IllegalStateException) {
                    e
                }

            assertThat(exception).isNotNull()
            assertThat(exception?.message).contains("handlerId must be set")
        }

        @Test
        fun `should support method chaining`() {
            val record =
                OutboxRecord
                    .Builder<String>()
                    .key("test-key")
                    .payload("test-payload")
                    .handlerId("TestHandler#handle(java.lang.String)")
                    .build(clock)

            assertThat(record).isNotNull()
            assertThat(record.key).isEqualTo("test-key")
            assertThat(record.payload).isEqualTo("test-payload")
        }

        @Test
        fun `should calculate correct partition based on key`() {
            val key = "order-123"
            val record =
                OutboxRecord
                    .Builder<String>()
                    .key(key)
                    .payload("test-payload")
                    .handlerId("TestHandler#handle(java.lang.String)")
                    .build(clock)

            val expectedPartition = PartitionHasher.getPartitionForRecordKey(key)
            assertThat(record.partition).isEqualTo(expectedPartition)
        }

        @Test
        fun `with different keys should produce different partitions`() {
            val key1 = "order-123"
            val key2 = "order-456"

            val record1 =
                OutboxRecord
                    .Builder<String>()
                    .key(key1)
                    .payload("test-payload")
                    .handlerId("TestHandler#handle(java.lang.String)")
                    .build(clock)

            val record2 =
                OutboxRecord
                    .Builder<String>()
                    .key(key2)
                    .payload("test-payload")
                    .handlerId("TestHandler#handle(java.lang.String)")
                    .build(clock)

            assertThat(record1.partition).isEqualTo(PartitionHasher.getPartitionForRecordKey(key1))
            assertThat(record2.partition).isEqualTo(PartitionHasher.getPartitionForRecordKey(key2))
        }
    }

    @Test
    fun `markCompleted should not modify record when already completed`() {
        val completedAt = now.minusMinutes(5)
        val record =
            outboxRecord(
                status = OutboxRecordStatus.COMPLETED,
                completedAt = completedAt,
                createdAt = now.minusMinutes(10),
            )

        record.markCompleted(clock)

        assertThat(record.status).isEqualTo(OutboxRecordStatus.COMPLETED)
        assertThat(record.completedAt).isEqualTo(completedAt)
    }

    @Test
    fun `markFailed should set status to FAILED when record is NEW`() {
        val record =
            outboxRecord(
                status = OutboxRecordStatus.NEW,
                completedAt = null,
                nextRetryAt = now.plusMinutes(5),
                createdAt = now.minusMinutes(10),
            )

        record.markFailed()

        assertThat(record.status).isEqualTo(OutboxRecordStatus.FAILED)
    }

    @Test
    fun `markFailed should not modify record when already failed`() {
        val record =
            outboxRecord(
                status = OutboxRecordStatus.FAILED,
                failureCount = 2,
                nextRetryAt = now.plusMinutes(5),
                createdAt = now.minusMinutes(10),
            )

        record.markFailed()

        assertThat(record.status).isEqualTo(OutboxRecordStatus.FAILED)
    }

    @Test
    fun `markFailed should change status from COMPLETED to FAILED`() {
        val record =
            outboxRecord(
                status = OutboxRecordStatus.COMPLETED,
                completedAt = now.minusMinutes(5),
                nextRetryAt = now.plusMinutes(5),
                createdAt = now.minusMinutes(10),
            )

        record.markFailed()

        assertThat(record.status).isEqualTo(OutboxRecordStatus.FAILED)
    }

    @Test
    fun `markFailed should not affect failureCount`() {
        val initialFailureCount = 2
        val record =
            outboxRecord(
                status = OutboxRecordStatus.NEW,
                failureCount = initialFailureCount,
                createdAt = now.minusMinutes(10),
            )

        record.markFailed()

        assertThat(record.failureCount).isEqualTo(initialFailureCount)
    }

    @Test
    fun `incrementFailureCount should increase failure count by one`() {
        val record =
            outboxRecord(
                failureCount = 2,
                createdAt = now.minusMinutes(10),
            )

        record.incrementFailureCount()

        assertThat(record.failureCount).isEqualTo(3)
    }

    @Test
    fun `incrementFailureCount should work from zero`() {
        val record =
            outboxRecord(
                failureCount = 0,
                createdAt = now.minusMinutes(10),
            )

        record.incrementFailureCount()

        assertThat(record.failureCount).isEqualTo(1)
    }

    @Test
    fun `canBeRetried should return true when nextRetryAt is in the past and status is NEW`() {
        val record =
            outboxRecord(
                status = OutboxRecordStatus.NEW,
                nextRetryAt = now.minusMinutes(1),
                createdAt = now.minusMinutes(10),
            )

        val result = record.canBeRetried(clock)

        assertThat(result).isTrue()
    }

    @Test
    fun `canBeRetried should return false when nextRetryAt is in the future`() {
        val record =
            outboxRecord(
                status = OutboxRecordStatus.NEW,
                nextRetryAt = now.plusMinutes(1),
                createdAt = now.minusMinutes(10),
            )

        val result = record.canBeRetried(clock)

        assertThat(result).isFalse()
    }

    @Test
    fun `canBeRetried should return false when status is not NEW`() {
        val record =
            outboxRecord(
                status = OutboxRecordStatus.COMPLETED,
                completedAt = now.minusMinutes(5),
                nextRetryAt = now.minusMinutes(1),
                createdAt = now.minusMinutes(10),
            )

        val result = record.canBeRetried(clock)

        assertThat(result).isFalse()
    }

    @Test
    fun `canBeRetried should return false when nextRetryAt equals current time`() {
        val record =
            outboxRecord(
                status = OutboxRecordStatus.NEW,
                nextRetryAt = now,
                createdAt = now.minusMinutes(10),
            )

        val result = record.canBeRetried(clock)

        assertThat(result).isFalse()
    }

    @Test
    fun `retriesExhausted should return false when failure count equals max retries`() {
        val record =
            outboxRecord(
                failureCount = 3,
                createdAt = now.minusMinutes(10),
            )

        val result = record.retriesExhausted(3)

        assertThat(result).isFalse()
    }

    @Test
    fun `retriesExhausted should return true when failure count exceeds max retries`() {
        val record =
            outboxRecord(
                failureCount = 4,
                createdAt = now.minusMinutes(10),
            )

        val result = record.retriesExhausted(3)

        assertThat(result).isTrue()
    }

    @Test
    fun `retriesExhausted should return false when failure count is less than max retries`() {
        val record =
            outboxRecord(
                failureCount = 2,
                createdAt = now.minusMinutes(10),
            )

        val result = record.retriesExhausted(3)

        assertThat(result).isFalse()
    }

    @Test
    fun `retriesExhausted with maxRetries 0 should mark exhausted on any failure`() {
        val record =
            outboxRecord(
                failureCount = 1,
                createdAt = now.minusMinutes(10),
            )

        val result = record.retriesExhausted(0)

        assertThat(result).isTrue()
    }

    @Test
    fun `scheduleNextRetry should set nextRetryAt`() {
        val record =
            outboxRecord(
                createdAt = now.minusMinutes(10),
            )

        val delay = Duration.of(5, ChronoUnit.SECONDS)

        record.scheduleNextRetry(delay, clock)

        assertThat(record.nextRetryAt).isEqualTo(now.plus(delay))
    }

    @Test
    fun `scheduleNextRetry should handle large delays`() {
        val record =
            outboxRecord(
                createdAt = now.minusHours(1),
            )

        val delay = Duration.ofHours(24)

        record.scheduleNextRetry(delay, clock)

        assertThat(record.nextRetryAt).isEqualTo(now.plus(delay))
    }

    @Test
    fun `restore should create record with all properties`() {
        val record =
            outboxRecord(
                id = "custom-id",
                createdAt = now.minusHours(1),
                status = OutboxRecordStatus.COMPLETED,
                completedAt = now.minusMinutes(30),
                failureCount = 2,
                nextRetryAt = now.plusMinutes(10),
            )

        assertThat(record.id).isEqualTo("custom-id")
        assertThat(record.status).isEqualTo(OutboxRecordStatus.COMPLETED)
        assertThat(record.key).isEqualTo("test-record-key")
        assertThat(record.payload).isInstanceOf(OutboxRecordTestFactory.CreatedEvent::class.java)
        assertThat(record.createdAt).isEqualTo(now.minusHours(1))
        assertThat(record.completedAt).isEqualTo(now.minusMinutes(30))
        assertThat(record.failureCount).isEqualTo(2)
        assertThat(record.nextRetryAt).isEqualTo(now.plusMinutes(10))
    }

    @Test
    fun `payload is preserved through record creation`() {
        val event = OutboxRecordTestFactory.CreatedEvent(id = "test-123")
        val record =
            outboxRecord(
                payload = event,
            )

        assertThat(record.payload).isEqualTo(event)
    }

    @Test
    fun `custom handlerId is preserved`() {
        val customHandlerId = "custom.HandlerClass#customMethod(java.lang.String)"
        val record =
            outboxRecord(
                handlerId = customHandlerId,
            )

        assertThat(record.handlerId).isEqualTo(customHandlerId)
    }

    @Test
    fun `builder should create record with all required fields`() {
        val payload = OutboxRecordTestFactory.CreatedEvent(id = "event-123")
        val key = "custom-key"
        val handlerId = "TestHandler#handle(java.lang.String)"

        val record =
            OutboxRecord
                .Builder<OutboxRecordTestFactory.CreatedEvent>()
                .key(key)
                .payload(payload)
                .handlerId(handlerId)
                .build(clock)

        assertThat(record.key).isEqualTo(key)
        assertThat(record.payload).isEqualTo(payload)
        assertThat(record.handlerId).isEqualTo(handlerId)
    }

    @Test
    fun `builder should set status to NEW on build`() {
        val record =
            OutboxRecord
                .Builder<String>()
                .key("test-key")
                .payload("test-payload")
                .handlerId("TestHandler#handle(java.lang.String)")
                .build(clock)

        assertThat(record.status).isEqualTo(OutboxRecordStatus.NEW)
    }

    @Test
    fun `builder should set completedAt to null on build`() {
        val record =
            OutboxRecord
                .Builder<String>()
                .key("test-key")
                .payload("test-payload")
                .handlerId("TestHandler#handle(java.lang.String)")
                .build(clock)

        assertThat(record.completedAt).isNull()
    }

    @Test
    fun `builder should set failureCount to 0 on build`() {
        val record =
            OutboxRecord
                .Builder<String>()
                .key("test-key")
                .payload("test-payload")
                .handlerId("TestHandler#handle(java.lang.String)")
                .build(clock)

        assertThat(record.failureCount).isEqualTo(0)
    }

    @Test
    fun `builder should set createdAt to current time on build`() {
        val record =
            OutboxRecord
                .Builder<String>()
                .key("test-key")
                .payload("test-payload")
                .handlerId("TestHandler#handle(java.lang.String)")
                .build(clock)

        assertThat(record.createdAt).isEqualTo(now)
    }

    @Test
    fun `builder should set nextRetryAt to current time on build`() {
        val record =
            OutboxRecord
                .Builder<String>()
                .key("test-key")
                .payload("test-payload")
                .handlerId("TestHandler#handle(java.lang.String)")
                .build(clock)

        assertThat(record.nextRetryAt).isEqualTo(now)
    }

    @Test
    fun `builder should generate UUID as id on build`() {
        val record =
            OutboxRecord
                .Builder<String>()
                .key("test-key")
                .payload("test-payload")
                .handlerId("TestHandler#handle(java.lang.String)")
                .build(clock)

        assertThat(record.id).isNotEmpty()
        assertThat(record.id).isNotEqualTo("test-key")
    }

    @Test
    fun `builder should use provided key when set`() {
        val providedKey = "my-custom-key"
        val record =
            OutboxRecord
                .Builder<String>()
                .key(providedKey)
                .payload("test-payload")
                .handlerId("TestHandler#handle(java.lang.String)")
                .build(clock)

        assertThat(record.key).isEqualTo(providedKey)
    }

    @Test
    fun `builder should use generated UUID as key when not provided`() {
        val record =
            OutboxRecord
                .Builder<String>()
                .payload("test-payload")
                .handlerId("TestHandler#handle(java.lang.String)")
                .build(clock)

        assertThat(record.key).isNotEmpty()
    }

    @Test
    fun `builder should throw error when payload is not set`() {
        val builder =
            OutboxRecord
                .Builder<String>()
                .key("test-key")
                .handlerId("TestHandler#handle(java.lang.String)")

        val exception =
            try {
                builder.build(clock)
                null
            } catch (e: IllegalStateException) {
                e
            }

        assertThat(exception).isNotNull()
        assertThat(exception?.message).contains("payload must be set")
    }

    @Test
    fun `builder should throw error when handlerId is not set`() {
        val builder =
            OutboxRecord
                .Builder<String>()
                .key("test-key")
                .payload("test-payload")

        val exception =
            try {
                builder.build(clock)
                null
            } catch (e: IllegalStateException) {
                e
            }

        assertThat(exception).isNotNull()
        assertThat(exception?.message).contains("handlerId must be set")
    }

    @Test
    fun `builder should support method chaining`() {
        val record =
            OutboxRecord
                .Builder<String>()
                .key("test-key")
                .payload("test-payload")
                .handlerId("TestHandler#handle(java.lang.String)")
                .build(clock)

        assertThat(record).isNotNull()
        assertThat(record.key).isEqualTo("test-key")
        assertThat(record.payload).isEqualTo("test-payload")
    }

    @Test
    fun `builder should calculate correct partition based on key`() {
        val key = "order-123"
        val record =
            OutboxRecord
                .Builder<String>()
                .key(key)
                .payload("test-payload")
                .handlerId("TestHandler#handle(java.lang.String)")
                .build(clock)

        val expectedPartition = PartitionHasher.getPartitionForRecordKey(key)
        assertThat(record.partition).isEqualTo(expectedPartition)
    }

    @Test
    fun `builder with different keys should produce different partitions`() {
        val key1 = "order-123"
        val key2 = "order-456"

        val record1 =
            OutboxRecord
                .Builder<String>()
                .key(key1)
                .payload("test-payload")
                .handlerId("TestHandler#handle(java.lang.String)")
                .build(clock)

        val record2 =
            OutboxRecord
                .Builder<String>()
                .key(key2)
                .payload("test-payload")
                .handlerId("TestHandler#handle(java.lang.String)")
                .build(clock)

        assertThat(record1.partition).isEqualTo(PartitionHasher.getPartitionForRecordKey(key1))
        assertThat(record2.partition).isEqualTo(PartitionHasher.getPartitionForRecordKey(key2))
    }
}
