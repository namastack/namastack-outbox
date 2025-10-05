package io.namastack.springoutbox

import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

class OutboxRecord internal constructor(
    val id: String,
    val aggregateId: String,
    val eventType: String,
    val payload: String,
    val createdAt: OffsetDateTime,
    status: OutboxRecordStatus,
    completedAt: OffsetDateTime?,
    retryCount: Int,
    nextRetryAt: OffsetDateTime,
) {
    var status: OutboxRecordStatus = status
        internal set

    var completedAt: OffsetDateTime? = completedAt
        internal set

    var retryCount: Int = retryCount
        internal set

    var nextRetryAt: OffsetDateTime = nextRetryAt
        internal set

    internal fun markCompleted(clock: Clock) {
        completedAt = OffsetDateTime.now(clock)
        status = OutboxRecordStatus.COMPLETED
    }

    internal fun markFailed() {
        status = OutboxRecordStatus.FAILED
    }

    internal fun incrementRetryCount() {
        retryCount++
    }

    internal fun canBeRetried(clock: Clock): Boolean =
        nextRetryAt.isBefore(OffsetDateTime.now(clock)) && status == OutboxRecordStatus.NEW

    internal fun retriesExhausted(maxRetries: Int): Boolean = retryCount >= maxRetries

    internal fun scheduleNextRetry(
        delay: Duration,
        clock: Clock,
    ) {
        this.nextRetryAt = OffsetDateTime.now(clock).plus(delay)
    }

    class Builder {
        private lateinit var aggregateId: String
        private lateinit var eventType: String
        private lateinit var payload: String

        fun aggregateId(aggregateId: String) = apply { this.aggregateId = aggregateId }

        fun eventType(eventType: String) = apply { this.eventType = eventType }

        fun payload(payload: String) = apply { this.payload = payload }

        fun build(clock: Clock = Clock.systemUTC()): OutboxRecord {
            val now = OffsetDateTime.now(clock)

            return OutboxRecord(
                id = UUID.randomUUID().toString(),
                status = OutboxRecordStatus.NEW,
                aggregateId = aggregateId,
                eventType = eventType,
                payload = payload,
                createdAt = now,
                completedAt = null,
                retryCount = 0,
                nextRetryAt = now,
            )
        }
    }

    companion object {
        fun restore(
            id: String,
            aggregateId: String,
            eventType: String,
            payload: String,
            createdAt: OffsetDateTime,
            status: OutboxRecordStatus,
            completedAt: OffsetDateTime?,
            retryCount: Int,
            nextRetryAt: OffsetDateTime,
        ): OutboxRecord =
            OutboxRecord(
                id = id,
                aggregateId = aggregateId,
                eventType = eventType,
                payload = payload,
                createdAt = createdAt,
                status = status,
                completedAt = completedAt,
                retryCount = retryCount,
                nextRetryAt = nextRetryAt,
            )
    }
}
