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
 * A failure can leave the commit outcome unknown; it does not necessarily mean the write rolled back.
 *
 * @author Roland Beisel
 * @since 1.12.0
 */
interface OutboxCompletionPersistence {
    /**
     * Persists the completed processing state of an existing record.
     *
     * @param record Record whose status and completion time have already been updated in memory
     * @throws IllegalStateException if batching rejects an active caller transaction or wraps a checked write failure
     * @throws RuntimeException if the persistence operation fails or its commit outcome cannot be confirmed
     */
    fun updateCompleted(record: OutboxRecord<*>)

    /**
     * Deletes a successfully processed record.
     *
     * @param id ID of the record to delete
     * @throws IllegalStateException if batching rejects an active caller transaction or wraps a checked write failure
     * @throws RuntimeException if the persistence operation fails or its commit outcome cannot be confirmed
     */
    fun deleteCompleted(id: String)
}
