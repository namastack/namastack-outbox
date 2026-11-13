package io.namastack.outbox.completion

import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository

/**
 * Delegates processing-state updates and deletions to the configured [OutboxRecordRepository].
 *
 * @param repository Repository used to update processing state or delete completed records
 *
 * @author Roland Beisel
 * @since 1.12.0
 */
class OutboxCompletionDirectPersistence(
    private val repository: OutboxRecordRepository,
) : OutboxCompletionPersistence {
    override fun updateProcessingState(record: OutboxRecord<*>) {
        repository.updateProcessingState(record)
    }

    override fun deleteCompleted(id: String) = repository.deleteById(id)
}
