package io.namastack.outbox.query

import io.namastack.outbox.toSingleLine

/**
 * SQL Server statements that inspect the first matching record once per candidate key.
 *
 * `CROSS APPLY` with `TOP (1)` performs an ordered head lookup. Strict eligibility is checked
 * afterwards, keeping failures, future retries and predecessors in other partitions as barriers.
 *
 * @author Roland Beisel
 * @since 1.12.0
 */
internal class SqlServerJdbcOutboxRecordQueries(
    tableName: String,
) : DefaultJdbcOutboxRecordQueries(tableName, { "OFFSET 0 ROWS FETCH NEXT :$it ROWS ONLY" }) {
    // Small requests retain direct lookups. Large requests first filter IDs using the narrow
    // pending index, then load their payloads. ID deduplication preserves IN-list semantics even
    // when the database collation considers two requested keys equal.
    override fun recordKeysParameter(recordKeys: Set<String>): Any = recordKeys.map { arrayOf(it) }

    override fun findByKeysAndStatus(keyCount: Int): String =
        if (keyCount <= 8) super.findByKeysAndStatus else findByKeysAndStatus

    override val findByKeysAndStatus: String =
        """
        SELECT record.*
        FROM (
            SELECT DISTINCT pending.id
            FROM $tableName pending
            INNER HASH JOIN (VALUES :recordKeys) requested(record_key)
                ON requested.record_key = pending.record_key
            WHERE pending.completed_at IS NULL
              AND pending.status = :status
        ) matching
        INNER LOOP JOIN $tableName record ON record.id = matching.id
        WHERE record.completed_at IS NULL
          AND record.status = :status
        ORDER BY record.record_key, record.created_at, record.id
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
            SELECT TOP (1) record_key, id, created_at, partition_no, status, next_retry_at
            FROM $tableName first_record
            WHERE first_record.record_key = candidate.record_key
              AND first_record.completed_at IS NULL
              ${if (strict) "" else "AND first_record.status = :status AND first_record.next_retry_at <= :now"}
            ORDER BY first_record.created_at, first_record.id
        ) head
        WHERE head.partition_no IN (:partitions)
          AND head.status = :status
          AND head.next_retry_at <= :now
    """.toSingleLine()

    override val findStrictRecordKeys: String =
        """
        SELECT head.record_key
        $strictHeads
        ORDER BY head.created_at, head.id
        OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY
    """.toSingleLine()

    override val findNonStrictRecordKeys: String =
        """
        SELECT head.record_key
        $nonStrictHeads
        ORDER BY head.created_at, head.id
        OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY
    """.toSingleLine()

    override val findOldestStrictDueRetry: String =
        """
        SELECT head.next_retry_at
        $strictHeads
        ORDER BY head.next_retry_at
        OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY
    """.toSingleLine()
}
