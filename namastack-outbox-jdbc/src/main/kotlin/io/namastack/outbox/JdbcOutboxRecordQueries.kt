package io.namastack.outbox

/**
 * Shared SQL for [JdbcOutboxRecordRepository].
 *
 * Uses shared window, predecessor or distinct-key queries to select eligible incomplete heads. The dialect
 * chooses the measured selection strategy and row-limit syntax; all eligibility predicates
 * remain portable. JDBC limits are retained for custom clients and unknown database products.
 * Unlimited requests and completed-status calls retain the original query semantics.
 *
 * @param tableNameResolver Resolver for the record table, captured once during construction
 * @param dialectProvider Lazy query dialect; construction resolves names without opening a connection
 *
 * @author Roland Beisel
 * @since 1.12.0
 */
internal class JdbcOutboxRecordQueries(
    tableNameResolver: JdbcTableNameResolver,
    dialectProvider: () -> JdbcOutboxQueryDialect = { JdbcOutboxQueryDialect() },
) {
    private val tableName = tableNameResolver.outboxRecord
    private val dialect by lazy(dialectProvider)

    /**
     * Query to update an existing outbox record.
     */
    val updateRecord =
        """
        UPDATE $tableName
        SET status = :status, record_key = :recordKey, record_type = :recordType, payload = :payload, context = :context,
            partition_no = :partitionNo, created_at = :createdAt, completed_at = :completedAt, failure_count = :failureCount,
            failure_reason = :failureReason, next_retry_at = :nextRetryAt, handler_id = :handlerId
        WHERE id = :id
        """.toSingleLine()

    /**
     * Query to update the processing state of an existing outbox record.
     */
    val updateProcessingState =
        """
        UPDATE $tableName
        SET status = :status, completed_at = :completedAt, failure_count = :failureCount,
            failure_reason = :failureReason, next_retry_at = :nextRetryAt
        WHERE id = :id
        """.toSingleLine()

    /**
     * Query to insert a new outbox record.
     */
    val insertRecord =
        """
        INSERT INTO $tableName
        (id, status, record_key, record_type, payload, context, partition_no,
         created_at, completed_at, failure_count, failure_reason, next_retry_at, handler_id)
        VALUES (:id, :status, :recordKey, :recordType, :payload, :context, :partitionNo,
         :createdAt, :completedAt, :failureCount, :failureReason, :nextRetryAt, :handlerId)
        """.toSingleLine()

    /**
     * Query to select outbox records by status ordered by creation time.
     */
    val findByStatus =
        """
        SELECT * FROM $tableName
        WHERE status = :status
        ORDER BY created_at, id
        """.toSingleLine()

    /**
     * Query to select outbox records by record key and status ordered by creation time.
     */
    val findByKeyAndStatus =
        """
        SELECT * FROM $tableName
        WHERE record_key = :recordKey AND status = :status
        ORDER BY created_at, id
        """.toSingleLine()

    /** Bulk equivalent of the per-key NEW-record read, including retries that are not yet due. */
    val findByKeysAndStatus =
        """
        SELECT * FROM $tableName
        WHERE record_key IN (:recordKeys) AND status = :status AND completed_at IS NULL
        ORDER BY record_key, created_at, id
        """.toSingleLine()

    /**
     * Query to select outbox records by status completed before a cutoff, oldest first.
     */
    val findByStatusAndCompletedBefore =
        """
        SELECT * FROM $tableName
        WHERE status = :status AND completed_at < :cutoff
        ORDER BY completed_at
        """.toSingleLine()

    /**
     * Query to select outbox records by status with next retry time before a cutoff, oldest first.
     */
    val findByStatusAndNextRetryBefore =
        """
        SELECT * FROM $tableName
        WHERE status = :status AND next_retry_at < :cutoff
        ORDER BY next_retry_at
        """.toSingleLine()

    /**
     * Query to select ids of outbox records by status completed before a cutoff, oldest first.
     */
    val findIdsByStatusAndCompletedBefore =
        """
        SELECT id FROM $tableName
        WHERE status = :status AND completed_at < :cutoff
        ORDER BY completed_at
        """.toSingleLine()

    /**
     * Query to select ids of outbox records by status with next retry time before a cutoff, oldest first.
     */
    val findIdsByStatusAndNextRetryBefore =
        """
        SELECT id FROM $tableName
        WHERE status = :status AND next_retry_at < :cutoff
        ORDER BY next_retry_at
        """.toSingleLine()

    /**
     * Candidates with no earlier incomplete record, used by the grouped compatibility queries.
     * Completed-status calls intentionally retain the original candidate predicate.
     */
    private val legacyStrictHeads =
        """
        FROM $tableName o
        WHERE o.partition_no IN (:partitions)
          AND o.status = :status
          AND o.next_retry_at <= :now
          AND NOT EXISTS (
            SELECT 1 FROM $tableName older
            WHERE older.record_key = o.record_key
              AND older.completed_at IS NULL
              AND (
                older.created_at < o.created_at
                OR (older.created_at = o.created_at AND older.id < o.id)
              )
          )
        """.toSingleLine()

    private val legacyStrictRecordKeys =
        "SELECT o.record_key $legacyStrictHeads GROUP BY o.record_key ORDER BY MIN(o.created_at) ASC"

    /**
     * Query to select all record keys with pending records, regardless of previous failures.
     * Used when ignoreRecordKeysWithPreviousFailure is false.
     */
    private val legacyNonStrictRecordKeys =
        """
        SELECT o.record_key
        FROM $tableName o
        WHERE o.partition_no IN (:partitions)
          AND o.status = :status
          AND o.next_retry_at <= :now
        GROUP BY o.record_key
        ORDER BY MIN(o.created_at) ASC
        """.toSingleLine()

    /**
     * Query to count outbox records by status.
     */
    val countByStatus =
        """
        SELECT COUNT(*) FROM $tableName
        WHERE status = :status
        """.toSingleLine()

    /**
     * Query to count outbox records by partition and status.
     */
    val countByPartitionStatus =
        """
        SELECT COUNT(*) FROM $tableName
        WHERE partition_no = :partitionNo AND status = :status
        """.toSingleLine()

    /**
     * Query to count records across the specified partitions by status.
     */
    val countByPartitionsStatus =
        """
        SELECT COUNT(*) FROM $tableName
        WHERE partition_no IN (:partitions) AND status = :status
        """.toSingleLine()

    /**
     * Finds the oldest creation time for the requested partitions and status, including future retries.
     * The shipped age indexes support this aggregate without changing its status or partition filters.
     */
    val oldestCreatedAtByPartitionsStatus =
        """
        SELECT MIN(created_at) FROM $tableName
        WHERE partition_no IN (:partitions) AND status = :status
        """.toSingleLine()

    private val legacyOldestStrictDueRetry = "SELECT MIN(o.next_retry_at) $legacyStrictHeads"

    private val oldestNonStrictDueRetry =
        """
        SELECT MIN(o.next_retry_at)
        FROM $tableName o
        WHERE o.partition_no IN (:partitions)
          AND o.status = :status
          AND o.next_retry_at <= :now
        """.toSingleLine()

    /**
     * Query to delete outbox records by status.
     */
    val deleteByStatus =
        """
        DELETE FROM $tableName
        WHERE status = :status
        """.toSingleLine()

    /**
     * Query to delete outbox records by record key and status.
     */
    val deleteByKeyAndStatus =
        """
        DELETE FROM $tableName
        WHERE record_key = :recordKey AND status = :status
        """.toSingleLine()

    /**
     * Query to delete outbox record by id.
     */
    val deleteById =
        """
        DELETE FROM $tableName
        WHERE id = :id
        """.toSingleLine()

    /**
     * Query to delete outbox records by ids.
     */
    val deleteByIds =
        """
        DELETE FROM $tableName
        WHERE id IN (:ids)
        """.toSingleLine()

    // Rank globally: filtering readiness or ownership first could hide a strict predecessor.
    private val incompleteRecordsByKey =
        """
        SELECT record_key, id, created_at, partition_no, status, next_retry_at,
               ROW_NUMBER() OVER (PARTITION BY record_key ORDER BY created_at, id) AS key_position
        FROM $tableName
        WHERE completed_at IS NULL
        """.toSingleLine()

    private val strictRecordKeys =
        """
        SELECT head.record_key
        FROM ($incompleteRecordsByKey) head
        WHERE head.key_position = 1
          AND head.partition_no IN (:partitions)
          AND head.status = :status
          AND head.next_retry_at <= :now
        ORDER BY head.created_at, head.id
        """.toSingleLine()

    private val nonStrictRecordKeys =
        """
        SELECT head.record_key
        FROM (
            SELECT record_key, id, created_at, partition_no,
                   ROW_NUMBER() OVER (PARTITION BY record_key ORDER BY created_at, id) AS key_position
            FROM $tableName
            WHERE completed_at IS NULL
              AND partition_no IN (:partitions)
              AND status = :status
              AND next_retry_at <= :now
        ) head
        WHERE head.key_position = 1
        ORDER BY head.created_at, head.id
        """.toSingleLine()

    private val oldestStrictDueRetry =
        """
        SELECT MIN(head.next_retry_at)
        FROM ($incompleteRecordsByKey) head
        WHERE head.key_position = 1
          AND head.partition_no IN (:partitions)
          AND head.status = :status
          AND head.next_retry_at <= :now
        """.toSingleLine()

    /** Scans due candidates in creation/id order while retaining every global predecessor barrier. */
    private val orderedStrictHeads =
        """
        FROM $tableName o
        WHERE o.partition_no IN (:partitions)
          AND o.status = :status
          AND o.completed_at IS NULL
          AND o.next_retry_at <= :now
          AND NOT EXISTS (
            SELECT 1 FROM $tableName earlier
            WHERE earlier.record_key = o.record_key
              AND earlier.completed_at IS NULL
              AND earlier.created_at <= o.created_at
              AND (earlier.created_at < o.created_at
                   OR (earlier.created_at = o.created_at AND earlier.id < o.id))
          )
        """.toSingleLine()

    private val orderedStrictRecordKeys =
        "SELECT o.record_key $orderedStrictHeads ORDER BY o.created_at, o.id"

    private val orderedOldestStrictDueRetry =
        "SELECT MIN(o.next_retry_at) $orderedStrictHeads"

    // Enumerate keys before resolving their global head. The inner DISTINCT matches the partition/key
    // index prefix; the outer DISTINCT removes keys present in several requested partitions.
    private val distinctStrictHeads by lazy {
        val firstRow = checkNotNull(dialect.limitClause).replace(":limit", "1")
        """
        FROM (
            SELECT DISTINCT candidate.record_key, (
                SELECT first_record.id
                FROM $tableName first_record
                WHERE first_record.record_key = candidate.record_key
                  AND first_record.completed_at IS NULL
                ORDER BY first_record.record_key, first_record.created_at, first_record.id
                $firstRow
            ) AS head_id
            FROM (
                SELECT DISTINCT completed_at, partition_no, record_key
                FROM $tableName
                WHERE completed_at IS NULL
                  AND partition_no IN (:partitions)
            ) candidate
        ) candidate_heads
        JOIN $tableName head ON head.id = candidate_heads.head_id
        WHERE head.partition_no IN (:partitions)
          AND head.status = :status
          AND head.next_retry_at <= :now
        """.toSingleLine()
    }

    /**
     * Builds the key-selection query with its limit applied after eligibility and ordering.
     * The repository also bounds JDBC results. Completed-status and unlimited calls retain
     * grouped selection for compatibility.
     *
     * @param status Status of the records to select
     * @param batchSize Maximum number of eligible keys, or zero for an unlimited result
     * @param strict Whether an earlier incomplete record prevents selection of the key
     * @return SQL with named partition, status and clock parameters and an optional row limit
     */
    fun recordKeys(
        status: OutboxRecordStatus,
        batchSize: Int,
        strict: Boolean,
    ): String {
        val query =
            when {
                status == OutboxRecordStatus.COMPLETED || batchSize == 0 ->
                    if (strict) legacyStrictRecordKeys else legacyNonStrictRecordKeys

                strict && dialect.keySelection == JdbcOutboxKeySelection.DISTINCT_HEADS ->
                    "SELECT head.record_key $distinctStrictHeads ORDER BY head.created_at, head.id"

                strict && dialect.keySelection == JdbcOutboxKeySelection.PREDECESSOR -> orderedStrictRecordKeys

                strict -> strictRecordKeys

                else -> nonStrictRecordKeys
            }
        val limit = if (batchSize > 0) dialect.limitClause else null
        return if (limit == null) query else "$query ${limit.replace(":limit", batchSize.toString())}"
    }

    /**
     * Builds the oldest-due-retry query using the same strict head barrier as key selection.
     * Completed-status calls retain their original candidate predicate.
     *
     * @param status Status of the records to consider
     * @param strict Whether an earlier incomplete record blocks the candidate
     * @return SQL aggregating the oldest eligible retry timestamp
     */
    fun oldestDueRetry(
        status: OutboxRecordStatus,
        strict: Boolean,
    ): String =
        when {
            !strict -> oldestNonStrictDueRetry

            status == OutboxRecordStatus.COMPLETED -> legacyOldestStrictDueRetry

            dialect.keySelection == JdbcOutboxKeySelection.DISTINCT_HEADS ->
                "SELECT MIN(head.next_retry_at) $distinctStrictHeads"

            dialect.keySelection == JdbcOutboxKeySelection.PREDECESSOR -> orderedOldestStrictDueRetry

            else -> oldestStrictDueRetry
        }
}
