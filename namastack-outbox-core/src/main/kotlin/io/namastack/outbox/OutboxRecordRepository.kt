package io.namastack.outbox

/**
 * Repository interface for managing outbox records.
 *
 * Provides methods for persisting, querying, and managing outbox records
 * in the underlying data store.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
interface OutboxRecordRepository {
    /**
     * Saves an outbox record to the repository.
     *
     * @param record The outbox record to save
     * @return The saved outbox record
     */
    fun save(record: OutboxRecord): OutboxRecord

    /**
     * Finds all pending outbox records that are ready for processing.
     * Implementations **must** return records sorted by creation time ascending
     *
     * @return List of pending outbox records
     */
    fun findPendingRecords(): List<OutboxRecord>

    /**
     * Finds all completed outbox records.
     * Implementations **must** return records sorted by creation time ascending
     *
     * @return List of completed outbox records
     */
    fun findCompletedRecords(): List<OutboxRecord>

    /**
     * Finds all failed outbox records.
     * Implementations **must** return records sorted by creation time ascending
     *
     * @return List of failed outbox records
     */
    fun findFailedRecords(): List<OutboxRecord>

    /**
     * Finds aggregate IDs that have pending records with the specified status.
     *
     * @param status The status to filter by
     * @return List of aggregate IDs with pending records
     */
    fun findAggregateIdsWithPendingRecords(status: OutboxRecordStatus): List<String>

    /**
     * Finds aggregate IDs that have failed records.
     *
     * @return List of aggregate IDs with failed records
     */
    fun findAggregateIdsWithFailedRecords(): List<String>

    /**
     * Finds all incomplete records for a specific aggregate ID.
     * Implementations **must** return records sorted by creation time ascending
     *
     * @param aggregateId The aggregate ID to search for
     * @return List of incomplete outbox records for the aggregate
     */
    fun findAllIncompleteRecordsByAggregateId(aggregateId: String): List<OutboxRecord>

    /**
     * Deletes all records with the specified status.
     *
     * @param status The status of records to delete
     */
    fun deleteByStatus(status: OutboxRecordStatus)

    /**
     * Deletes records for a specific aggregate ID and status.
     *
     * @param aggregateId The aggregate ID
     * @param status The status of records to delete
     */
    fun deleteByAggregateIdAndStatus(
        aggregateId: String,
        status: OutboxRecordStatus,
    )
}
