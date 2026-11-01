package io.namastack.outbox.query

import io.namastack.outbox.toSingleLine

/**
 * Oracle statements: `FETCH FIRST ... ROWS ONLY`, ordering by `SYS_EXTRACT_UTC` so that the order of
 * `TIMESTAMP WITH TIME ZONE` index entries can be used, a status-based outer predicate matching the
 * status-leading index, and a `FIRST_ROWS(1)` hint for the oldest due record.
 *
 * @author Aleksander Zamojski
 * @since 1.12.0
 */
internal class OracleJdbcOutboxRecordQueries(
    tableName: String,
) : DefaultJdbcOutboxRecordQueries(tableName, { "FETCH FIRST :$it ROWS ONLY" }) {
    /**
     * The outer predicate can use the status-leading index. Ordering by `SYS_EXTRACT_UTC(o.created_at)`
     * matches the timestamp representation used by Oracle index entries.
     */
    override val findStrictRecordKeys: String =
        """
        SELECT o.record_key
        FROM $tableName o
        WHERE o.partition_no IN (:partitions)
          AND o.status = :status
          AND o.next_retry_at <= :now
          AND NOT EXISTS (
            SELECT 1
            FROM $tableName earlier
            WHERE earlier.record_key = o.record_key
              AND earlier.completed_at IS NULL
              AND earlier.created_at <= o.created_at
              AND (
                earlier.created_at < o.created_at
                OR (earlier.created_at = o.created_at AND earlier.id < o.id)
              )
          )
        ORDER BY SYS_EXTRACT_UTC(o.created_at), o.id
        FETCH FIRST :limit ROWS ONLY
        """.toSingleLine()

    /**
     * Like [findStrictRecordKeys], with the predecessor check limited to earlier due
     * records with the same status.
     */
    override val findNonStrictRecordKeys: String =
        """
        SELECT o.record_key
        FROM $tableName o
        WHERE o.partition_no IN (:partitions)
          AND o.status = :status
          AND o.next_retry_at <= :now
          AND NOT EXISTS (
            SELECT 1
            FROM $tableName earlier
            WHERE earlier.record_key = o.record_key
              AND earlier.completed_at IS NULL
              AND earlier.status = :status
              AND earlier.next_retry_at <= :now
              AND earlier.created_at <= o.created_at
              AND (
                earlier.created_at < o.created_at
                OR (earlier.created_at = o.created_at AND earlier.id < o.id)
              )
          )
        ORDER BY SYS_EXTRACT_UTC(o.created_at), o.id
        FETCH FIRST :limit ROWS ONLY
        """.toSingleLine()

    /**
     * `FIRST_ROWS(1)` encourages a plan that returns the first eligible record quickly. The optimizer
     * still chooses the execution plan according to the available indexes and statistics.
     */
    override val findOldestStrictDueRetry: String =
        """
        SELECT /*+ FIRST_ROWS(1) */ o.next_retry_at
        FROM $tableName o
        WHERE o.partition_no IN (:partitions)
          AND o.status = :status
          AND o.next_retry_at <= :now
          AND NOT EXISTS (
            SELECT 1
            FROM $tableName earlier
            WHERE earlier.record_key = o.record_key
              AND earlier.completed_at IS NULL
              AND earlier.created_at <= o.created_at
              AND (
                earlier.created_at < o.created_at
                OR (earlier.created_at = o.created_at AND earlier.id < o.id)
              )
          )
        ORDER BY SYS_EXTRACT_UTC(o.next_retry_at)
        FETCH FIRST :limit ROWS ONLY
        """.toSingleLine()
}
