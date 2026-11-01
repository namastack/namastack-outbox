package io.namastack.outbox.query

import io.namastack.outbox.OutboxRecordStatus
import io.namastack.outbox.OutboxRecordStatus.COMPLETED
import io.namastack.outbox.toSingleLine

/**
 * PostgreSQL statements that select the first incomplete record of each key before checking readiness.
 *
 * A set-based head selection avoids a correlated scan for every candidate when statistics still
 * describe an empty pending working set. Strict eligibility is checked only after selecting each
 * head, so failed records, future retries and predecessors in other partitions remain barriers.
 * Bulk loading also reads the requested keys in one statement rather than repeating a lateral probe.
 *
 * @author Roland Beisel
 * @since 1.12.0
 */
internal class PostgresJdbcOutboxRecordQueries(
    tableName: String,
) : DefaultJdbcOutboxRecordQueries(tableName, { "LIMIT :$it" }) {
    private val incompleteHeads =
        """
        SELECT DISTINCT ON (record_key) record_key, id, created_at, partition_no, status, next_retry_at
        FROM $tableName
        WHERE completed_at IS NULL
        ORDER BY record_key, created_at, id
        """.toSingleLine()

    override val findStrictRecordKeys: String =
        """
        SELECT head.record_key
        FROM ($incompleteHeads) head
        WHERE head.partition_no IN (:partitions)
          AND head.status = :status
          AND head.next_retry_at <= :now
        ORDER BY head.created_at, head.id
        LIMIT :limit
        """.toSingleLine()

    override val findNonStrictRecordKeys: String =
        """
        SELECT head.record_key
        FROM (
            SELECT DISTINCT ON (record_key) record_key, id, created_at, partition_no
            FROM $tableName
            WHERE completed_at IS NULL
              AND status = :status
              AND next_retry_at <= :now
            ORDER BY record_key, created_at, id
        ) head
        WHERE head.partition_no IN (:partitions)
        ORDER BY head.created_at, head.id
        LIMIT :limit
        """.toSingleLine()

    override val findOldestStrictDueRetry: String =
        """
        SELECT head.next_retry_at
        FROM ($incompleteHeads) head
        WHERE head.partition_no IN (:partitions)
          AND head.status = :status
          AND head.next_retry_at <= :now
        ORDER BY head.next_retry_at
        LIMIT :limit
        """.toSingleLine()

    override val findByKeysAndStatus: String =
        """
        SELECT *
        FROM $tableName
        WHERE record_key = ANY(CAST(ARRAY[:recordKeys] AS varchar[]))
          AND completed_at IS NULL
          AND status = :status
        ORDER BY record_key, created_at, id
        """.toSingleLine()

    // These predicates let PostgreSQL use the incomplete-record indexes even with a generic
    // prepared plan. Completed-status operations retain the full status-based predicate.
    private val findIncompleteByStatus =
        """
        SELECT * FROM $tableName
        WHERE completed_at IS NULL AND status = :status
        ORDER BY created_at, id
        """.toSingleLine()

    private val oldestIncompleteCreatedAt =
        """
        SELECT created_at FROM $tableName
        WHERE completed_at IS NULL AND status = :status AND partition_no IN (:partitions)
        ORDER BY created_at
        LIMIT :limit
        """.toSingleLine()

    private val oldestIncompleteDueRetry =
        """
        SELECT MIN(next_retry_at) FROM $tableName
        WHERE completed_at IS NULL AND status = :status AND partition_no IN (:partitions)
          AND next_retry_at <= :now
        """.toSingleLine()

    private val deleteIncompleteByStatus =
        """
        DELETE FROM $tableName
        WHERE completed_at IS NULL AND status = :status
        """.toSingleLine()

    override fun findByStatus(status: OutboxRecordStatus): String =
        if (status == COMPLETED) super.findByStatus(status) else findIncompleteByStatus

    override fun oldestCreatedAtByPartitionsStatus(status: OutboxRecordStatus): String =
        if (status == COMPLETED) super.oldestCreatedAtByPartitionsStatus(status) else oldestIncompleteCreatedAt

    override fun findOldestDueRetry(status: OutboxRecordStatus): String =
        if (status == COMPLETED) super.findOldestDueRetry(status) else oldestIncompleteDueRetry

    override fun deleteByStatus(status: OutboxRecordStatus): String =
        if (status == COMPLETED) super.deleteByStatus(status) else deleteIncompleteByStatus

    override val findByStatusAndNextRetryBefore: String =
        """
        SELECT * FROM $tableName
        WHERE completed_at IS NULL AND status = :status AND next_retry_at < :cutoff
        ORDER BY next_retry_at
        LIMIT :limit
        """.toSingleLine()

    override val findIdsByStatusAndNextRetryBefore: String =
        """
        SELECT id FROM $tableName
        WHERE completed_at IS NULL AND status = :status AND next_retry_at < :cutoff
        ORDER BY next_retry_at
        LIMIT :limit
        """.toSingleLine()
}
