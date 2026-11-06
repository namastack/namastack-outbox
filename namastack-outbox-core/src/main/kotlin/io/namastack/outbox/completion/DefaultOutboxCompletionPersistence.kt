package io.namastack.outbox.completion

import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository

/**
 * Delegates completion writes to the configured [OutboxRecordRepository].
 *
 * @param repository Repository used to update or delete completed records
 *
 * @since 1.12.0
 */
class DefaultOutboxCompletionPersistence(
    private val repository: OutboxRecordRepository,
) : OutboxCompletionPersistence {
    override fun updateCompleted(record: OutboxRecord<*>) {
        repository.updateProcessingState(record)
    }

    override fun deleteCompleted(id: String) = repository.deleteById(id)
}
