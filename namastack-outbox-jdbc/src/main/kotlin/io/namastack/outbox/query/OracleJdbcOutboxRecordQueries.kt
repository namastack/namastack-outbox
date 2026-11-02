package io.namastack.outbox.query

import io.namastack.outbox.toSingleLine

/**
 * Oracle statements that use ordered head probes once per candidate key.
 *
 * Candidates are collected without loading payloads. Strict readiness is checked only after
 * finding the global first incomplete record, so filtering cannot bypass a retry or failure barrier.
 * UTC timestamp ordering matches Oracle's index representation of timestamps with time zones.
 *
 * @author Roland Beisel
 * @since 1.12.0
 */
internal class OracleJdbcOutboxRecordQueries(
    tableName: String,
) : DefaultJdbcOutboxRecordQueries(tableName, { "FETCH FIRST :$it ROWS ONLY" }) {
    // Column-based hints work with manually named indexes too. They prevent stale NEW-status
    // estimates from turning each head probe into a scan of the entire pending set.
    override val findByKeysAndStatus: String =
        """
        SELECT /*+ INDEX(o (record_key)) */ o.*
        FROM $tableName o
        WHERE o.record_key IN (:recordKeys)
          AND o.completed_at IS NULL
          AND o.status = :status
        ORDER BY o.record_key, SYS_EXTRACT_UTC(o.created_at), o.id
    """.toSingleLine()

    private val strictHeads = candidateHeads(strict = true)
    private val nonStrictHeads = candidateHeads(strict = false)

    private fun candidateHeads(strict: Boolean): String =
        """
        FROM (
            SELECT DISTINCT record_key
            FROM $tableName
            WHERE completed_at IS NULL
              AND partition_no IN (:partitions)
              AND status = :status
              AND next_retry_at <= :now
        ) candidate
        CROSS APPLY (
            SELECT /*+ INDEX(first_record (record_key)) */
                   record_key, id, created_at, partition_no, status, next_retry_at
            FROM $tableName first_record
            WHERE first_record.record_key = candidate.record_key
              AND first_record.completed_at IS NULL
              ${if (strict) "" else "AND first_record.status = :status AND first_record.next_retry_at <= :now"}
            ORDER BY SYS_EXTRACT_UTC(first_record.created_at), first_record.id
            FETCH FIRST 1 ROWS ONLY
        ) head
        WHERE head.partition_no IN (:partitions)
          AND head.status = :status
          AND head.next_retry_at <= :now
    """.toSingleLine()

    override val findStrictRecordKeys: String =
        """
        SELECT head.record_key
        $strictHeads
        ORDER BY SYS_EXTRACT_UTC(head.created_at), head.id
        FETCH FIRST :limit ROWS ONLY
    """.toSingleLine()

    override val findNonStrictRecordKeys: String =
        """
        SELECT head.record_key
        $nonStrictHeads
        ORDER BY SYS_EXTRACT_UTC(head.created_at), head.id
        FETCH FIRST :limit ROWS ONLY
    """.toSingleLine()

    override val findOldestStrictDueRetry: String =
        """
        SELECT head.next_retry_at
        $strictHeads
        ORDER BY SYS_EXTRACT_UTC(head.next_retry_at)
        FETCH FIRST :limit ROWS ONLY
    """.toSingleLine()
}
