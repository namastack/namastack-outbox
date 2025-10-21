package io.namastack.outbox

import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Represents an outbox record for implementing the transactional outbox pattern.
 *
 * An outbox record stores event information that needs to be published reliably
 * after a database transaction has been committed. This ensures that domain events
 * are not lost even if the message publishing fails.
 *
 * @param id Unique identifier for the outbox record
 * @param aggregateId Identifier of the aggregate that produced this event
 * @param eventType Type/name of the event
 * @param payload Event payload in serialized form (typically JSON)
 * @param createdAt Timestamp when the record was created
 * @param status Current processing status of the record
 * @param completedAt Timestamp when processing was completed (null if not completed)
 * @param retryCount Number of retry attempts made
 * @param nextRetryAt Timestamp for the next retry attempt
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
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
    /**
     * Current processing status of this outbox record.
     */
    var status: OutboxRecordStatus = status
        internal set

    /**
     * Timestamp when the record processing was completed.
     * Null if the record has not been completed yet.
     */
    var completedAt: OffsetDateTime? = completedAt
        internal set

    /**
     * Number of retry attempts made for processing this record.
     */
    var retryCount: Int = retryCount
        internal set

    /**
     * Timestamp when the next retry attempt should be made.
     */
    var nextRetryAt: OffsetDateTime = nextRetryAt
        internal set

    /**
     * Marks this record as completed and sets the completion timestamp.
     *
     * @param clock Clock to use for determining the current time
     */
    internal fun markCompleted(clock: Clock) {
        completedAt = OffsetDateTime.now(clock)
        status = OutboxRecordStatus.COMPLETED
    }

    /**
     * Marks this record as failed.
     */
    internal fun markFailed() {
        status = OutboxRecordStatus.FAILED
    }

    /**
     * Increments the retry count for this record.
     */
    internal fun incrementRetryCount() {
        retryCount++
    }

    /**
     * Checks if this record can be retried based on timing and status.
     *
     * @param clock Clock to use for determining the current time
     * @return true if the record can be retried, false otherwise
     */
    internal fun canBeRetried(clock: Clock): Boolean =
        nextRetryAt.isBefore(OffsetDateTime.now(clock)) && status == OutboxRecordStatus.NEW

    /**
     * Checks if the maximum number of retries has been exhausted.
     *
     * @param maxRetries Maximum allowed number of retries
     * @return true if retries are exhausted, false otherwise
     */
    internal fun retriesExhausted(maxRetries: Int): Boolean = retryCount >= maxRetries

    /**
     * Schedules the next retry attempt.
     *
     * @param delay Delay duration before the next retry
     * @param clock Clock to use for determining the current time
     */
    internal fun scheduleNextRetry(
        delay: Duration,
        clock: Clock,
    ) {
        this.nextRetryAt = OffsetDateTime.now(clock).plus(delay)
    }

    /**
     * Builder class for creating new OutboxRecord instances.
     */
    class Builder {
        private lateinit var aggregateId: String
        private lateinit var eventType: String
        private lateinit var payload: String

        /**
         * Sets the aggregate ID for the outbox record.
         *
         * @param aggregateId Identifier of the aggregate
         * @return this Builder instance for method chaining
         */
        fun aggregateId(aggregateId: String) = apply { this.aggregateId = aggregateId }

        /**
         * Sets the event type for the outbox record.
         *
         * @param eventType Type/name of the event
         * @return this Builder instance for method chaining
         */
        fun eventType(eventType: String) = apply { this.eventType = eventType }

        /**
         * Sets the payload for the outbox record.
         *
         * @param payload Event payload in serialized form
         * @return this Builder instance for method chaining
         */
        fun payload(payload: String) = apply { this.payload = payload }

        /**
         * Builds the OutboxRecord with the configured values.
         *
         * @param clock Clock to use for timestamps (defaults to system UTC)
         * @return A new OutboxRecord instance
         */
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
        /**
         * Restores an OutboxRecord from persisted data.
         *
         * This method is used when loading records from the database.
         *
         * @param id Unique identifier
         * @param aggregateId Aggregate identifier
         * @param eventType Event type
         * @param payload Event payload
         * @param createdAt Creation timestamp
         * @param status Current status
         * @param completedAt Completion timestamp (may be null)
         * @param retryCount Number of retries
         * @param nextRetryAt Next retry timestamp
         * @return Restored OutboxRecord instance
         */
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
