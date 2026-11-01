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
     * The outer scan uses the status-leading index, so it has no `o.completed_at IS NULL` condition, and
     * orders by `SYS_EXTRACT_UTC(o.created_at)` because that is the order of the index entries.
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
     * `FIRST_ROWS(1)` makes the optimizer read `(status, next_retry_at)` in order and stop at the first
     * record that passes the predecessor check; without the hint it reads all due records.
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
