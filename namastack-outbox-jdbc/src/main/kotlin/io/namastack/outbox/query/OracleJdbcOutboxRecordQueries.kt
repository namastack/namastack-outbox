package io.namastack.outbox.query

import io.namastack.outbox.toSingleLine

/**
 * Oracle statements: `FETCH FIRST ... ROWS ONLY`, adapted to the status-leading indexes.
 *
 * Oracle has no partial indexes and its status-leading indexes do not contain `completed_at`, so the
 * status-led statements have no outer `completed_at IS NULL` condition: it would force a table access per
 * row. The status alone selects the same rows, because `completed_at` is `NULL` exactly for `NEW` and
 * `FAILED` records. `TIMESTAMP WITH TIME ZONE` index entries sort by `SYS_EXTRACT_UTC`, so ordering by that
 * expression lets the index order serve the statement; the result order is the same instant order.
 *
 * @author Aleksander Zamojski
 * @since 1.12.0
 */
internal class OracleJdbcOutboxRecordQueries(
    tableName: String,
) : DefaultJdbcOutboxRecordQueries(tableName, "FETCH FIRST :limit ROWS ONLY") {
    override val findStrictRecordKeys: String =
        """
        SELECT o.record_key
        FROM $tableName o
        WHERE o.partition_no IN (:partitions)
          AND o.status = :status
          AND o.next_retry_at <= :now
          AND $noEarlierIncompleteRecord
        ORDER BY SYS_EXTRACT_UTC(o.created_at), o.id
        $rowLimitClause
        """.toSingleLine()

    override val findNonStrictRecordKeys: String =
        """
        SELECT o.record_key
        FROM $tableName o
        WHERE o.partition_no IN (:partitions)
          AND o.status = :status
          AND o.next_retry_at <= :now
          AND $noEarlierDueRecord
        ORDER BY SYS_EXTRACT_UTC(o.created_at), o.id
        $rowLimitClause
        """.toSingleLine()

    override val oldestCreatedAtByPartitionsStatus: String =
        """
        SELECT MIN(created_at)
        FROM $tableName
        WHERE partition_no IN (:partitions)
          AND status = :status
        """.toSingleLine()

    /**
     * `FIRST_ROWS(1)` makes the optimizer read `(status, next_retry_at)` in order and stop at the first
     * record that passes the predecessor check.
     */
    override val oldestDueStrict: String =
        """
        SELECT /*+ FIRST_ROWS(1) */ o.next_retry_at
        FROM $tableName o
        WHERE o.partition_no IN (:partitions)
          AND o.status = :status
          AND o.next_retry_at <= :now
          AND $noEarlierIncompleteRecord
        ORDER BY SYS_EXTRACT_UTC(o.next_retry_at)
        $rowLimitClause
        """.toSingleLine()

    override val oldestDueNonStrict: String =
        """
        SELECT MIN(o.next_retry_at)
        FROM $tableName o
        WHERE o.partition_no IN (:partitions)
          AND o.status = :status
          AND o.next_retry_at <= :now
        """.toSingleLine()
}
