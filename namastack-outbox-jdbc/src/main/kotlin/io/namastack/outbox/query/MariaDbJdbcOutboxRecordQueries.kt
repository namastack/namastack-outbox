package io.namastack.outbox.query

import io.namastack.outbox.toSingleLine

/**
 * MariaDB statements that probe one incomplete head per candidate key instead of per pending record.
 *
 * Candidate filtering only reduces the keys to inspect. The head lookup still sees every incomplete
 * record of a key, including failed records and records in another partition or waiting for retry.
 * Eligibility is checked again on that head before applying the result limit.
 *
 * @author Roland Beisel
 * @since 1.12.0
 */
internal class MariaDbJdbcOutboxRecordQueries(
    tableName: String,
) : DefaultJdbcOutboxRecordQueries(tableName, { "LIMIT :$it" }) {
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
        JOIN $tableName head ON head.id = (
            SELECT first_record.id
            FROM $tableName first_record
            WHERE first_record.record_key = candidate.record_key
              AND first_record.completed_at IS NULL
              ${if (strict) "" else "AND first_record.status = :status AND first_record.next_retry_at <= :now"}
            ORDER BY first_record.created_at, first_record.id
            LIMIT 1
        )
        WHERE head.partition_no IN (:partitions)
          AND head.status = :status
          AND head.next_retry_at <= :now
    """.toSingleLine()

    override val findStrictRecordKeys: String =
        """
        SELECT head.record_key
        $strictHeads
        ORDER BY head.created_at, head.id
        LIMIT :limit
    """.toSingleLine()

    override val findNonStrictRecordKeys: String =
        """
        SELECT head.record_key
        $nonStrictHeads
        ORDER BY head.created_at, head.id
        LIMIT :limit
    """.toSingleLine()

    override val findOldestStrictDueRetry: String =
        """
        SELECT head.next_retry_at
        $strictHeads
        ORDER BY head.next_retry_at
        LIMIT :limit
    """.toSingleLine()
}
