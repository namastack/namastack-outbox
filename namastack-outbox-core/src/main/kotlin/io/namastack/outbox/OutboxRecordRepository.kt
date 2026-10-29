package io.namastack.outbox

import java.time.Instant

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
    fun <T> save(record: OutboxRecord<T>): OutboxRecord<T>

    /**
     * Creates a new outbox record.
     *
     * @param record The new outbox record
     * @return The created outbox record
     * @since 1.12.0
     */
    fun <T> create(record: OutboxRecord<T>): OutboxRecord<T>

    /**
     * Updates an existing outbox record. If the record no longer exists, nothing is written.
     *
     * @param record The outbox record to update
     * @return `true` if the record was updated, `false` if it no longer exists
     * @since 1.12.0
     */
    fun <T> update(record: OutboxRecord<T>): Boolean

    /**
     * Persists processing metadata after [record] has been marked completed.
     *
     * Implementations may update only status, completion time and retry/failure metadata,
     * without serializing or replacing the stored payload, context or routing fields.
     * Handler mutations of payload or context must not rely on this operation being persisted.
     * The default delegates to [update] for compatibility with custom repositories.
     * Like [update], this must not insert a missing record.
     *
     * @return `true` if the record was updated, `false` if it no longer exists
     * @since 1.12.0
     */
    fun updateCompletion(record: OutboxRecord<*>): Boolean = update(record)

    /**
     * Runs one key's processing synchronously. The default adds no transaction boundary.
     * Decorators may group writes, but must finish their commit/rollback before returning so
     * partition assignments cannot change while completion persistence is still in flight.
     * Grouping writes can replay an already handled prefix after a rollback or crash;
     * implementations must document that behavior and must not enable it implicitly.
     */
    fun <T> withRecordKeyProcessing(
        recordKey: String,
        action: () -> T,
    ): T = action()

    /**
     * Finds all pending outbox records that are ready for processing.
     * Implementations **must** return records sorted by creation time ascending, then by record ID
     * ascending to resolve equal timestamps. IDs define a stable tie-breaker, not creation order.
     *
     * @return List of pending outbox records
     */
    fun findPendingRecords(): List<OutboxRecord<*>>

    /**
     * Finds all completed outbox records.
     * Implementations **must** return records sorted by creation time ascending
     *
     * @return List of completed outbox records
     */
    fun findCompletedRecords(): List<OutboxRecord<*>>

    /**
     * Finds `COMPLETED` records completed before the given instant.
     *
     * Implementations **must** return records sorted by completion time ascending (oldest first)
     * and **must** return at most [limit] records. The method is not scoped to partitions; callers
     * are responsible for coordinating retention across instances.
     *
     * @param completedBefore Exclusive upper bound for `completedAt`
     * @param limit Maximum number of records to return, must be positive
     * @return Matching records, oldest first
     * @throws IllegalArgumentException if [limit] is not positive
     * @since 1.11.0
     */
    fun findCompletedRecords(
        completedBefore: Instant,
        limit: Int,
    ): List<OutboxRecord<*>>

    /**
     * Finds all failed outbox records.
     * Implementations **must** return records sorted by creation time ascending
     *
     * @return List of failed outbox records
     */
    fun findFailedRecords(): List<OutboxRecord<*>>

    /**
     * Finds `FAILED` records whose last scheduled attempt was before the given instant.
     *
     * The age of a failed record is measured by `nextRetryAt`, which keeps the scheduled time of
     * the last attempt when a record is marked as failed. It is a lower bound for the failure time,
     * not the exact time the record became `FAILED`: a record created at 10:00 and first processed
     * and permanently failed at 18:00 still has `nextRetryAt` 10:00.
     *
     * Implementations **must** return records sorted by `nextRetryAt` ascending (oldest first)
     * and **must** return at most [limit] records. The method is not scoped to partitions.
     *
     * @param lastRetryBefore Exclusive upper bound for `nextRetryAt`
     * @param limit Maximum number of records to return, must be positive
     * @return Matching records, oldest first
     * @throws IllegalArgumentException if [limit] is not positive
     * @since 1.11.0
     */
    fun findFailedRecords(
        lastRetryBefore: Instant,
        limit: Int,
    ): List<OutboxRecord<*>>

    /**
     * Finds all incomplete records for a specific record key.
     * Implementations **must** return records sorted by creation time ascending, then by record ID
     * ascending, consistently with the predecessor check in [findRecordKeysInPartitions].
     *
     * @param recordKey The record key to search for
     * @return List of incomplete outbox records for the given record key
     */
    fun findIncompleteRecordsByRecordKey(recordKey: String): List<OutboxRecord<*>>

    /**
     * Loads one ordered page for every requested key, including empty pages for keys with no rows.
     * Implementations can fetch several keys in one query and defer payload decoding until access.
     * [maxRecordsPerKey] is zero for unlimited records. Each page uses the same ordering as
     * [findIncompleteRecordsByRecordKey]. The default defers each existing per-key query until
     * its worker accesses the page, preserving failure isolation for custom repositories.
     */
    fun findIncompleteRecordPages(
        recordKeys: Set<String>,
        maxRecordsPerKey: Int = 0,
    ): Map<String, OutboxRecordPage> {
        require(maxRecordsPerKey >= 0)
        return recordKeys.associateWith { key ->
            DeferredOutboxRecordPage({ findIncompleteRecordsByRecordKey(key) }, maxRecordsPerKey)
        }
    }

    /**
     * Finds record keys that have pending records in specific partitions.
     *
     * The query logic depends on the ignoreRecordKeysWithPreviousFailure flag:
     * - If true: only record keys with no previous open/failed record (older.completedAt is null) are returned.
     * - If false: all record keys with pending records are returned, regardless of previous failures.
     * Previous records are determined by the same creation-time and record-ID ordering used by
     * [findIncompleteRecordsByRecordKey].
     *
     * @param partitions List of partition numbers to search in
     * @param status The status to filter by
     * @param batchSize Maximum number of record keys to return
     * @param ignoreRecordKeysWithPreviousFailure Whether to exclude record keys with previous open/failed records
     * @return List of record keys with pending records in the specified partitions
     */
    fun findRecordKeysInPartitions(
        partitions: Set<Int>,
        status: OutboxRecordStatus,
        batchSize: Int,
        ignoreRecordKeysWithPreviousFailure: Boolean,
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
     * Counts records across the specified partitions by status, regardless of their next retry time.
     *
     * Returns zero without querying the data store when [partitions] is empty.
     * The default implementation calls [countRecordsByPartition] once per partition and sums the
     * results for compatibility with custom repositories. Persistence implementations should override
     * this method with a single aggregate query for non-empty sets.
     *
     * @param partitions The partition numbers to count
     * @param status The status to count
     * @return Total number of matching records, or zero if the set is empty or no records match
     */
    fun countRecordsByPartitions(
        partitions: Set<Int>,
        status: OutboxRecordStatus,
    ): Long = partitions.sumOf { countRecordsByPartition(it, status) }

    /**
     * Finds the earliest creation time of records across the specified partitions by status,
     * regardless of their next retry time.
     *
     * Returns `null` without querying the data store when [partitions] is empty.
     * Persistence implementations must provide this method using a single aggregate query.
     *
     * @param partitions The partition numbers to search in
     * @param status The status to filter by
     * @return Earliest `createdAt` of the matching records, or `null` if the set is empty or no records match
     * @since 1.12.0
     */
    fun findOldestCreatedAtByPartitions(
        partitions: Set<Int>,
        status: OutboxRecordStatus,
    ): Instant?

    /**
     * Finds the earliest next retry time of records across the specified partitions that are due
     * for processing at [now].
     *
     * Uses the same eligibility predicate as [findRecordKeysInPartitions]:
     * - If [ignoreRecordKeysWithPreviousFailure] is true: records blocked by an earlier incomplete
     *   record with the same record key are excluded.
     * - If false: all due records are considered, regardless of earlier incomplete records.
     *
     * The result can include records that are currently being processed.
     *
     * Returns `null` without querying the data store when [partitions] is empty.
     * Persistence implementations must provide this method using a single aggregate query.
     *
     * @param partitions The partition numbers to search in
     * @param status The status to filter by
     * @param now Inclusive upper bound for `nextRetryAt`
     * @param ignoreRecordKeysWithPreviousFailure Whether to exclude records blocked by earlier incomplete records
     * @return Earliest `nextRetryAt` of the matching records, or `null` if the set is empty or no records match
     * @since 1.12.0
     */
    fun findOldestDueNextRetryAtByPartitions(
        partitions: Set<Int>,
        status: OutboxRecordStatus,
        now: Instant,
        ignoreRecordKeysWithPreviousFailure: Boolean,
    ): Instant?

    /**
     * Deletes all records with the specified status.
     *
     * @param status The status of records to delete
     */
    fun deleteByStatus(status: OutboxRecordStatus)

    /**
     * Deletes records for a specific record key and status.
     *
     * @param recordKey The record key
     * @param status The status of records to delete
     */
    fun deleteByRecordKeyAndStatus(
        recordKey: String,
        status: OutboxRecordStatus,
    )

    /**
     * Deletes a record by its unique ID.
     *
     * @param id The unique identifier of the outbox record
     */
    fun deleteById(id: String)

    /**
     * Deletes the records with the given ids regardless of their status.
     *
     * Unknown ids are ignored. Returns zero without querying the data store when [ids] is empty.
     * Callers must not pass ids of `NEW` records unless they intend to drop them unprocessed.
     *
     * @param ids Ids of the records to delete
     * @return Number of records actually deleted
     * @since 1.11.0
     */
    fun deleteByIds(ids: Collection<String>): Int

    /**
     * Deletes at most [limit] `COMPLETED` records completed before the given instant, oldest first.
     *
     * Eligible ids are selected first and then deleted with [deleteByIds], so [limit] is subject to the
     * same `IN` list limits. A record that changes between both steps is still deleted. Records are not
     * deserialized, so records whose payload type no longer exists are deleted as well. To delete
     * everything eligible, call this method repeatedly until it returns less than [limit].
     *
     * @param completedBefore Exclusive upper bound for `completedAt`
     * @param limit Maximum number of records to delete, must be positive
     * @return Number of records actually deleted
     * @throws IllegalArgumentException if [limit] is not positive
     * @since 1.11.0
     */
    fun deleteCompletedRecords(
        completedBefore: Instant,
        limit: Int,
    ): Int

    /**
     * Deletes at most [limit] `FAILED` records whose last scheduled attempt was before the given instant,
     * oldest first.
     *
     * The age of a failed record is measured by `nextRetryAt`, see [findFailedRecords].
     *
     * **Ordering:** a `FAILED` record blocks later records with the same record key when
     * `stop-on-first-failure` is enabled. Deleting it releases that barrier, and the later records
     * of the key are processed afterwards.
     *
     * Eligible ids are selected first and then deleted with [deleteByIds], so [limit] is subject to the
     * same `IN` list limits. A record that changes between both steps is still deleted, for example a
     * `FAILED` record that is reset to `NEW` in the meantime. Records are not deserialized, so records
     * whose payload type no longer exists are deleted as well. To delete everything eligible, call this
     * method repeatedly until it returns less than [limit].
     *
     * @param lastRetryBefore Exclusive upper bound for `nextRetryAt`
     * @param limit Maximum number of records to delete, must be positive
     * @return Number of records actually deleted
     * @throws IllegalArgumentException if [limit] is not positive
     * @since 1.11.0
     */
    fun deleteFailedRecords(
        lastRetryBefore: Instant,
        limit: Int,
    ): Int
}
