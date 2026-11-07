package io.namastack.outbox.query

import io.namastack.outbox.toSingleLine

/**
 * MariaDB statements: the portable statements with `LIMIT`, and four statements shaped for the MariaDB
 * planner.
 *
 * The key and oldest-time statements read the incomplete records of the owned partitions in
 * `(created_at, id)` or `next_retry_at` order and stop at the row limit. The predecessor check is a scalar
 * subquery that returns the first incomplete record of the key. It runs only for the rows the outer scan
 * reads, so the row limit bounds its executions.
 *
 * @author Aleksander Zamojski
 * @since 1.12.0
 */
internal class MariaDbJdbcOutboxRecordQueries(
    tableName: String,
) : DefaultJdbcOutboxRecordQueries(tableName, "LIMIT :limit") {
    /**
     * Keeps a record `o` only if it is the first incomplete record of its key
     * (`o.id = (SELECT ... ORDER BY created_at, id LIMIT 1)`), so a key with an earlier incomplete record
     * of any status or due time is excluded. The outer scan reads
     * `idx_outbox_record_status_completed_created_id` in `(created_at, id)` order and the row limit ends it
     * early. The subquery reads the first entry of the key in `idx_outbox_record_key_completed_created_id`.
     */
    override val findStrictRecordKeys: String =
        """
        SELECT o.record_key
        FROM $tableName o
        WHERE o.partition_no IN (:partitions)
          AND o.status = :status
          AND o.next_retry_at <= :now
          AND o.completed_at IS NULL
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
     * `idx_outbox_record_status_completed_created_id` in `(created_at, id)` order and the row limit ends it
     * early.
     */
    override val findNonStrictRecordKeys: String =
        """
        SELECT o.record_key
        FROM $tableName o
        WHERE o.partition_no IN (:partitions)
          AND o.status = :status
          AND o.next_retry_at <= :now
          AND o.completed_at IS NULL
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

    /**
     * Reads the creation time of the oldest incomplete record of the owned partitions with the given status.
     * Ordering by `(created_at, id)` with a row limit of one lets the planner read
     * `idx_outbox_record_status_completed_created_id` in order and stop at the first row of an owned
     * partition. No row means no record.
     */
    override val oldestCreatedAtByPartitionsStatus: String =
        """
        SELECT created_at
        FROM $tableName
        WHERE partition_no IN (:partitions)
          AND status = :status
          AND completed_at IS NULL
        ORDER BY created_at, id
        $rowLimitClause
        """.toSingleLine()

    /**
     * Reads the earliest retry time of the due incomplete records of the owned partitions. Ordering by
     * `next_retry_at` with a row limit of one lets the planner read `idx_outbox_record_status_completed_retry`
     * in order and stop at the first row of an owned partition. No row means no due record.
     */
    override val oldestDueNonStrict: String =
        """
        SELECT o.next_retry_at
        FROM $tableName o
        WHERE o.partition_no IN (:partitions)
          AND o.status = :status
          AND o.next_retry_at <= :now
          AND o.completed_at IS NULL
        ORDER BY o.next_retry_at
        $rowLimitClause
        """.toSingleLine()
}
