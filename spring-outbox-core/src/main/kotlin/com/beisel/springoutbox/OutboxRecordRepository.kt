package com.beisel.springoutbox

/**
 * Public API for saving outbox records.
 * This is the main repository interface that external projects should use to interact with the outbox pattern.
 */
interface OutboxRecordRepository {
    /**
     * Saves an outbox record for later processing.
     *
     * @param record The outbox record to save
     * @return The saved outbox record
     */
    fun save(record: OutboxRecord): OutboxRecord

    fun findPendingRecords(): List<OutboxRecord>

    fun findCompletedRecords(): List<OutboxRecord>

    fun findFailedRecords(): List<OutboxRecord>

    // Internal methods needed by OutboxScheduler
    fun findAggregateIdsWithPendingRecords(status: OutboxRecordStatus): List<String>

    fun findAggregateIdsWithFailedRecords(): List<String>

    fun findAllIncompleteRecordsByAggregateId(aggregateId: String): List<OutboxRecord>
}
