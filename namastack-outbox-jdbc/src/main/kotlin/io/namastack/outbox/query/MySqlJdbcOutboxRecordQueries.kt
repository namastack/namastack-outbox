package io.namastack.outbox.query

import io.namastack.outbox.toSingleLine

/**
 * MySQL statements: the portable statements with `LIMIT`, and the record key statements shaped for the
 * MySQL planner.
 *
 * The record key statements read the records of the owned partitions in `(created_at, id)` order and stop
 * at the row limit. The predecessor check is a scalar subquery that returns the first incomplete record of
 * the key. It runs only for the rows the outer scan reads, so the row limit bounds its executions.
 *
 * @author Aleksander Zamojski
 * @since 1.12.0
 */
internal class MySqlJdbcOutboxRecordQueries(
    tableName: String,
) : DefaultJdbcOutboxRecordQueries(tableName, "LIMIT :limit") {
    /**
     * Keeps a record `o` only if it is the first incomplete record of its key
     * (`o.id = (SELECT ... ORDER BY created_at, id LIMIT 1)`), so a key with an earlier incomplete record
     * of any status or due time is excluded. The outer scan reads `idx_outbox_record_status_created_id` in
     * `(created_at, id)` order and the row limit ends it early. The subquery reads the first entry of the
     * key in `idx_outbox_record_key_completed_created_id`.
     */
    override val findStrictRecordKeys: String =
        """
        SELECT o.record_key
        FROM $tableName o
        WHERE o.partition_no IN (:partitions)
          AND o.status = :status
          AND o.next_retry_at <= :now
          AND o.id = (
            SELECT e.id
            FROM $tableName e
            WHERE e.record_key = o.record_key
              AND e.completed_at IS NULL
            ORDER BY e.created_at, e.id
            LIMIT 1
          )
        ORDER BY o.created_at, o.id
        $rowLimitClause
        """.toSingleLine()

    /**
     * Keeps a record `o` only if it is the first due incomplete record of its key with the given status
     * (`o.id = (SELECT ... ORDER BY created_at, id LIMIT 1)`). The outer scan reads
     * `idx_outbox_record_status_created_id` in `(created_at, id)` order and the row limit ends it early.
     */
    override val findNonStrictRecordKeys: String =
        """
        SELECT o.record_key
        FROM $tableName o
        WHERE o.partition_no IN (:partitions)
          AND o.status = :status
          AND o.next_retry_at <= :now
          AND o.id = (
            SELECT e.id
            FROM $tableName e
            WHERE e.record_key = o.record_key
              AND e.completed_at IS NULL
              AND e.status = :status
              AND e.next_retry_at <= :now
            ORDER BY e.created_at, e.id
            LIMIT 1
          )
        ORDER BY o.created_at, o.id
        $rowLimitClause
        """.toSingleLine()
}
