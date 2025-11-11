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
     * @param batchSize Maximum number of aggregate IDs to return
     * @return List of aggregate IDs with pending records
     */
    fun findAggregateIdsWithPendingRecords(
        status: OutboxRecordStatus,
        batchSize: Int,
    ): List<String>

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

    /**
     * Deletes a record by its unique ID.
     *
     * @param id The unique identifier of the outbox record
     */
    fun deleteById(id: String)

    /**
     * Finds aggregate IDs that have pending records in specific partitions.
     *
     * @param partitions List of partition numbers to search in
     * @param status The status to filter by
     * @param batchSize Maximum number of aggregate IDs to return
     * @return List of aggregate IDs with pending records in the specified partitions
     */
    fun findAggregateIdsInPartitions(
        partitions: List<Int>,
        status: OutboxRecordStatus,
        batchSize: Int,
    ): List<String>

    /**
     * Counts records in a specific partition by status.
     *
     * @param partition The partition number
     * @param status The status to count
     * @return Number of records in the partition with the specified status
     */
    fun countRecordsByPartition(
        partition: Int,
        status: OutboxRecordStatus,
    ): Long

    /**
     * Finds all records in a specific partition.
     *
     * @param partition The partition number
     * @param status The status to filter by (optional)
     * @return List of records in the partition
     */
    fun findRecordsByPartition(
        partition: Int,
        status: OutboxRecordStatus? = null,
    ): List<OutboxRecord>
}
