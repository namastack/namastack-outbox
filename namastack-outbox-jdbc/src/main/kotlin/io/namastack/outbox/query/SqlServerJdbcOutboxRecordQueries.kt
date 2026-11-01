package io.namastack.outbox.query

import io.namastack.outbox.toSingleLine

/**
 * SQL Server statements: `OFFSET 0 ROWS FETCH NEXT ... ROWS ONLY`. The record-key query without the
 * previous-failure filter keeps the grouped form, which the SQL Server planner handles better.
 *
 * @author Aleksander Zamojski
 * @since 1.12.0
 */
internal class SqlServerJdbcOutboxRecordQueries(
    tableName: String,
) : DefaultJdbcOutboxRecordQueries(tableName, { "OFFSET 0 ROWS FETCH NEXT :$it ROWS ONLY" }) {
    override val findNonStrictRecordKeys: String =
        """
        SELECT o.record_key
        FROM $tableName o
        WHERE o.partition_no IN (:partitions)
          AND o.status = :status
          AND o.next_retry_at <= :now
        GROUP BY o.record_key
        ORDER BY MIN(o.created_at) ASC
        OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY
        """.toSingleLine()
}
