package io.namastack.outbox.completion

import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository

/**
 * Persists the completion of a successfully processed outbox record.
 *
 * Each method returns only after its persistence operation completes. Implementations that queue
 * writes must wait for the database transaction to commit and propagate failures, so processing
 * can preserve record-key order. Retry and permanent-failure state changes use
 * [OutboxRecordRepository] directly.
 *
 * @since 1.12.0
 */
interface OutboxCompletionPersistence {
    /**
     * Persists the completed processing state of an existing record.
     *
     * @param record Record whose status and completion time have already been updated in memory
     */
    fun updateCompleted(record: OutboxRecord<*>)

    /**
     * Deletes a successfully processed record.
     *
     * @param id ID of the record to delete
     */
    fun deleteCompleted(id: String)
}
