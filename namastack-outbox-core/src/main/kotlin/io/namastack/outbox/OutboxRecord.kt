package io.namastack.outbox

import io.namastack.outbox.partition.PartitionHasher
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
 * @param recordKey Identifier of the logical group or entity for this outbox record
 * @param recordType Type/name of the event
 * @param payload Event payload in serialized form (typically JSON)
 * @param createdAt Timestamp when the record was created
 * @param status Current processing status of the record
 * @param completedAt Timestamp when processing was completed (null if not completed)
 * @param retryCount Number of retry attempts made
 * @param nextRetryAt Timestamp for the next retry attempt
 * @param processorName Name of the processor that should handle this record
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
class OutboxRecord internal constructor(
    val id: String,
    val recordKey: String,
    val recordType: String,
    val payload: String,
    val createdAt: OffsetDateTime,
    val partition: Int,
    status: OutboxRecordStatus,
    completedAt: OffsetDateTime?,
    retryCount: Int,
    nextRetryAt: OffsetDateTime,
    processorName: String,
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
     * Processor bean name for this outbox record.
     */
    var processorName: String = processorName
        internal set

    /**
     * Marks this record as completed and sets the completion timestamp.
     * Only changes the status and completedAt time if the record is not already completed.
     *
     * @param clock Clock to use for determining the current time
     */
    internal fun markCompleted(clock: Clock) {
        if (status != OutboxRecordStatus.COMPLETED) {
            completedAt = OffsetDateTime.now(clock)
            status = OutboxRecordStatus.COMPLETED
        }
    }

    /**
     * Marks this record as failed.
     * Only changes the status if the record is not already failed.
     */
    internal fun markFailed() {
        if (status != OutboxRecordStatus.FAILED) {
            status = OutboxRecordStatus.FAILED
        }
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
        private lateinit var recordKey: String
        private lateinit var recordType: String
        private lateinit var payload: String
        private lateinit var processorName: String

        /**
         * Sets the record key for the outbox record.
         *
         * @param recordKey Key that uniquely identifies the logical group for this outbox record
         * @return this Builder instance for method chaining
         */
        fun recordKey(recordKey: String) = apply { this.recordKey = recordKey }

        /**
         * Sets the record type for the outbox record.
         *
         * @param recordType Type/name of the event
         * @return this Builder instance for method chaining
         */
        fun recordType(recordType: String) = apply { this.recordType = recordType }

        /**
         * Sets the payload for the outbox record.
         *
         * @param payload Event payload in serialized form
         * @return this Builder instance for method chaining
         */
        fun payload(payload: String) = apply { this.payload = payload }

        /**
         * Sets the processor bean name for the outbox record.
         *
         * @param processorName Name of the processor bean
         * @return this Builder instance for method chaining
         */
        fun processorName(processorName: String) = apply { this.processorName = processorName }

        /**
         * Builds the OutboxRecord with the configured values.
         *
         * @param clock Clock to use for timestamps (defaults to system UTC)
         * @return A new OutboxRecord instance
         */
        fun build(clock: Clock): OutboxRecord {
            val now = OffsetDateTime.now(clock)
            val partition = PartitionHasher.getPartitionForKey(recordKey)

            return OutboxRecord(
                id = UUID.randomUUID().toString(),
                status = OutboxRecordStatus.NEW,
                recordKey = recordKey,
                recordType = recordType,
                payload = payload,
                partition = partition,
                createdAt = now,
                completedAt = null,
                retryCount = 0,
                nextRetryAt = now,
                processorName = processorName,
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
         * @param recordKey Logical group identifier
         * @param recordType Event type
         * @param payload Event payload
         * @param partition Partition for this record
         * @param createdAt Creation timestamp
         * @param status Current status
         * @param completedAt Completion timestamp
         * @param retryCount Number of retries
         * @param nextRetryAt Next retry timestamp
         * @param processorName Processor bean name
         * @return Restored OutboxRecord instance
         */
        fun restore(
            id: String,
            recordKey: String,
            recordType: String,
            payload: String,
            createdAt: OffsetDateTime,
            status: OutboxRecordStatus,
            completedAt: OffsetDateTime?,
            retryCount: Int,
            partition: Int,
            nextRetryAt: OffsetDateTime,
            processorName: String,
        ): OutboxRecord =
            OutboxRecord(
                id = id,
                recordKey = recordKey,
                recordType = recordType,
                payload = payload,
                partition = partition,
                createdAt = createdAt,
                status = status,
                completedAt = completedAt,
                retryCount = retryCount,
                nextRetryAt = nextRetryAt,
                processorName = processorName,
            )
    }
}
