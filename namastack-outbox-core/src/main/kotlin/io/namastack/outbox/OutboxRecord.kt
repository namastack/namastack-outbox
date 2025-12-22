package io.namastack.outbox

import io.namastack.outbox.OutboxRecordStatus.NEW
import io.namastack.outbox.partition.PartitionHasher
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Represents an outbox record for implementing the transactional outbox pattern.
 *
 * Stores information that needs to be published reliably after a database transaction
 * has been committed. Tracks processing status and manages retry behavior through
 * failure count and scheduled retry times.
 *
 * @param T Type of the payload
 * @param id Unique identifier for the outbox record
 * @param key Logical group identifier for this record
 * @param payload Payload data
 * @param createdAt Timestamp when the record was created
 * @param status Current processing status of the record
 * @param completedAt Timestamp when processing was completed (null if not completed)
 * @param failureCount Number of times this record has failed processing
 * @param nextRetryAt Timestamp for the next retry attempt
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
class OutboxRecord<T> internal constructor(
    val id: String,
    val key: String,
    val payload: T,
    val context: Map<String, String>,
    val partition: Int,
    val createdAt: OffsetDateTime,
    val handlerId: String,
    status: OutboxRecordStatus,
    completedAt: OffsetDateTime?,
    failureCount: Int,
    failureException: Throwable?,
    failureReason: String?,
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
     * Number of times this record has failed processing.
     * Incremented each time processing fails and a retry is scheduled.
     */
    var failureCount: Int = failureCount
        internal set

    /**
     * Timestamp when the next retry attempt should be made.
     */
    var nextRetryAt: OffsetDateTime = nextRetryAt
        internal set

    /**
     * Exception from the last failure, if any.
     */
    var failureException: Throwable? = failureException
        internal set

    /**
     * Reason for the last failure, if any.
     * This can be used to store error messages or other diagnostic information.
     */
    var failureReason: String? = failureReason
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
     * Increments the failure count for this record.
     */
    internal fun incrementFailureCount() {
        failureCount++
    }

    /**
     * Checks if this record can be retried based on timing and status.
     *
     * @param clock Clock to use for determining the current time
     * @return true if the record can be retried, false otherwise
     */
    internal fun canBeRetried(clock: Clock): Boolean = nextRetryAt.isBefore(OffsetDateTime.now(clock)) && status == NEW

    /**
     * Checks if the maximum number of retries has been exhausted.
     *
     * @param maxRetries Maximum allowed number of retries
     * @return true if retries are exhausted, false otherwise
     */
    internal fun retriesExhausted(maxRetries: Int): Boolean = failureCount > maxRetries

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
     * Updates the failure reason for this record.
     * This can be used to store error messages or other diagnostic information
     * about why the record processing has failed.
     *
     * @param rawMessage The raw failure message
     * @param maxLength The maximum length of the failure reason string
     */
    internal fun updateFailureReason(
        rawMessage: String?,
        maxLength: Int = 1000,
    ) {
        failureReason =
            rawMessage
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { if (it.length <= maxLength) it else it.substring(0, maxLength) }
    }

    /**
     * Updates the failure exception for this record.
     *
     * @param exception The exception that caused the failure
     */
    internal fun updateFailureException(exception: Throwable?) {
        failureException = exception
        updateFailureReason(exception?.message)
    }

    /**
     * Builder class for creating new OutboxRecord instances.
     */
    class Builder<T> {
        private var key: String? = null
        private var payload: T? = null
        private var context: Map<String, String>? = null
        private var handlerId: String? = null

        /**
         * Sets the record key for the outbox record.
         *
         * @param key Identifier of the logical group of the record
         * @return this Builder instance for method chaining
         */
        fun key(key: String) = apply { this.key = key }

        /**
         * Sets the payload for the outbox record.
         *
         * @param payload Record payload
         * @return this Builder instance for method chaining
         */
        fun payload(payload: T) = apply { this.payload = payload }

        /**
         * Sets the context map for the outbox record.
         *
         * @param context Context metadata (e.g., tracing IDs, tenant IDs)
         * @return this Builder instance for method chaining
         */
        fun context(context: Map<String, String>) = apply { this.context = context }

        /**
         * Sets the handler ID for the outbox record.
         *
         * @param handlerId Identifier of the handler responsible for processing the record
         * @return this Builder instance for method chaining
         */
        fun handlerId(handlerId: String) = apply { this.handlerId = handlerId }

        /**
         * Builds the OutboxRecord with the configured values.
         *
         * @param clock Clock to use for timestamps (defaults to system UTC)
         * @return A new OutboxRecord instance
         */
        fun build(clock: Clock): OutboxRecord<T> {
            val id = UUID.randomUUID().toString()
            val rk = key ?: id
            val pl = payload ?: error("payload must be set")
            val ctx = context ?: emptyMap()
            val hId = handlerId ?: error("handlerId must be set")

            val now = OffsetDateTime.now(clock)
            val partition = PartitionHasher.getPartitionForRecordKey(rk)

            return OutboxRecord(
                id = id,
                status = NEW,
                key = rk,
                payload = pl,
                context = ctx,
                partition = partition,
                createdAt = now,
                completedAt = null,
                failureCount = 0,
                failureException = null,
                failureReason = null,
                nextRetryAt = now,
                handlerId = hId,
            )
        }
    }

    companion object {
        /**
         * Restores an OutboxRecord from persisted data.
         *
         * This method is used when loading records from the database.
         *
         * @param T Type of the payload
         * @param id Unique identifier
         * @param recordKey Record key
         * @param payload Payload
         * @param partition Partition for this record
         * @param createdAt Creation timestamp
         * @param status Current status
         * @param completedAt Completion timestamp
         * @param failureCount Number of failures
         * @param nextRetryAt Next retry timestamp
         * @return Restored OutboxRecord instance
         */
        @JvmStatic
        fun <T> restore(
            id: String,
            recordKey: String,
            payload: T,
            context: Map<String, String>,
            createdAt: OffsetDateTime,
            status: OutboxRecordStatus,
            completedAt: OffsetDateTime?,
            failureCount: Int,
            failureException: Throwable?,
            failureReason: String?,
            partition: Int,
            nextRetryAt: OffsetDateTime,
            handlerId: String,
        ): OutboxRecord<T> =
            OutboxRecord(
                id = id,
                key = recordKey,
                payload = payload,
                context = context,
                partition = partition,
                createdAt = createdAt,
                status = status,
                completedAt = completedAt,
                failureCount = failureCount,
                failureException = failureException,
                failureReason = failureReason,
                nextRetryAt = nextRetryAt,
                handlerId = handlerId,
            )
    }
}
