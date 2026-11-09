package io.namastack.outbox.completion

import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordStatus
import java.time.Instant

/**
 * Data needed to persist the completion of one outbox record through a batch writer.
 *
 * [Update] captures the record's processing fields; [Delete] identifies the record to remove.
 * Neither action retains the payload, context, or mutable [OutboxRecord].
 *
 * @author Roland Beisel
 * @since 1.12.0
 */
sealed interface OutboxCompletion {
    /** ID of the record affected by this action. */
    val recordId: String

    /**
     * Processing-state snapshot for an update completion.
     *
     * @property recordId ID of the record to update
     * @property status Status to persist
     * @property completedAt Completion timestamp to persist, if present
     * @property failureCount Number of processing failures to persist
     * @property failureReason Last failure reason to persist, if present
     * @property nextRetryAt Next retry time to persist
     * @author Roland Beisel
     */
    data class Update(
        override val recordId: String,
        val status: OutboxRecordStatus,
        val completedAt: Instant?,
        val failureCount: Int,
        val failureReason: String?,
        val nextRetryAt: Instant,
    ) : OutboxCompletion {
        companion object {
            /**
             * Creates an update from the record's current processing state.
             *
             * @param record Record to copy the processing state from
             * @return Update containing the copied state
             */
            fun from(record: OutboxRecord<*>): Update =
                Update(
                    recordId = record.id,
                    status = record.status,
                    completedAt = record.completedAt,
                    failureCount = record.failureCount,
                    failureReason = record.failureReason,
                    nextRetryAt = record.nextRetryAt,
                )
        }
    }

    /**
     * Record identifier for a delete completion.
     *
     * @property recordId ID of the record to delete
     * @author Roland Beisel
     */
    data class Delete(
        override val recordId: String,
    ) : OutboxCompletion
}
