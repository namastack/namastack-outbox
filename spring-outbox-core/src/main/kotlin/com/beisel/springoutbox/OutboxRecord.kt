package com.beisel.springoutbox

import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

@ConsistentCopyVisibility
data class OutboxRecord internal constructor(
    val id: String,
    var status: OutboxRecordStatus = OutboxRecordStatus.NEW,
    val aggregateId: String,
    val eventType: String,
    val payload: String,
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
    var completedAt: OffsetDateTime? = null,
    var retryCount: Int = 0,
    var nextRetryAt: OffsetDateTime = OffsetDateTime.now(),
) {
    fun markCompleted() {
        completedAt = OffsetDateTime.now()
        status = OutboxRecordStatus.COMPLETED
    }

    fun markFailed() {
        status = OutboxRecordStatus.FAILED
    }

    fun incrementRetryCount() {
        retryCount++
    }

    fun canBeRetried(clock: Clock): Boolean =
        nextRetryAt.isBefore(OffsetDateTime.now(clock)) && status == OutboxRecordStatus.NEW

    fun retriesExhausted(maxRetries: Int): Boolean = retryCount >= maxRetries

    fun scheduleNextRetry(delay: Duration) {
        this.nextRetryAt = nextRetryAt.plus(delay)
    }

    class Builder {
        private var id: String = UUID.randomUUID().toString()
        private var status: OutboxRecordStatus = OutboxRecordStatus.NEW
        private lateinit var aggregateId: String
        private lateinit var eventType: String
        private lateinit var payload: String
        private var createdAt: OffsetDateTime = OffsetDateTime.now()
        private var completedAt: OffsetDateTime? = null
        private var retryCount: Int = 0
        private var nextRetryAt: OffsetDateTime = OffsetDateTime.now()

        fun id(id: String) = apply { this.id = id }

        fun status(status: OutboxRecordStatus) = apply { this.status = status }

        fun aggregateId(aggregateId: String) = apply { this.aggregateId = aggregateId }

        fun eventType(eventType: String) = apply { this.eventType = eventType }

        fun payload(payload: String) = apply { this.payload = payload }

        fun createdAt(createdAt: OffsetDateTime) = apply { this.createdAt = createdAt }

        fun completedAt(completedAt: OffsetDateTime?) = apply { this.completedAt = completedAt }

        fun retryCount(retryCount: Int) = apply { this.retryCount = retryCount }

        fun nextRetryAt(nextRetryAt: OffsetDateTime) = apply { this.nextRetryAt = nextRetryAt }

        fun build(): OutboxRecord =
            OutboxRecord(
                id = id,
                status = status,
                aggregateId = aggregateId,
                eventType = eventType,
                payload = payload,
                createdAt = createdAt,
                completedAt = completedAt,
                retryCount = retryCount,
                nextRetryAt = nextRetryAt,
            )
    }
}
