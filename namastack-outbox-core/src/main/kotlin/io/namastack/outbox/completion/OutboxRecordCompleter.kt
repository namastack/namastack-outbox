package io.namastack.outbox.completion

import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.runtime.OutboxRuntimeSettings
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * Completes successfully processed records according to the runtime's deletion setting.
 *
 * The processor waits for [complete] before advancing to the next record for the same key.
 *
 * @param persistence Stores the chosen completion action
 * @param settings Processing settings that determine whether completed records are deleted
 * @param clock Clock used to set the completion time when records are retained
 *
 * @author Roland Beisel
 * @since 1.12.0
 */
class OutboxRecordCompleter(
    private val persistence: OutboxCompletionPersistence,
    private val settings: OutboxRuntimeSettings.Processing,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(OutboxRecordCompleter::class.java)

    /**
     * Deletes the record or marks it completed and persists its processing state.
     *
     * If persisting the completed state throws an exception, restores the record's previous
     * in-memory status and completion time. The next poll reloads the database state.
     *
     * @param record Record whose handler has completed successfully
     * @throws Exception if the completion write fails
     */
    fun complete(record: OutboxRecord<*>) {
        if (settings.deleteCompletedRecords) {
            log.trace("Deleting completed record {}", record.id)
            persistence.deleteCompleted(record.id)
        } else {
            log.trace("Marking record {} as COMPLETED", record.id)
            val previousStatus = record.status
            val previousCompletedAt = record.completedAt
            try {
                record.markCompleted(clock)
                persistence.updateCompleted(record)
            } catch (exception: Exception) {
                record.status = previousStatus
                record.completedAt = previousCompletedAt
                throw exception
            }
        }
    }
}
