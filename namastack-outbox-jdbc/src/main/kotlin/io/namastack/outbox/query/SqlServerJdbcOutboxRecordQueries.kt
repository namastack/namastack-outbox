package io.namastack.outbox.query

import io.namastack.outbox.toSingleLine

/**
 * SQL Server statements: an `OFFSET 0 ROWS FETCH NEXT ... ROWS ONLY` row-limit clause, and the oldest-record
 * statements that select one `MIN` aggregate row. All other statements are those of
 * [DefaultJdbcOutboxRecordQueries], which the composite indexes of the SQL Server schema script serve.
 *
 * @author Aleksander Zamojski
 * @since 2.0.0
 */
internal class SqlServerJdbcOutboxRecordQueries(
    tableName: String,
) : DefaultJdbcOutboxRecordQueries(tableName, "OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY") {
    /** Selects `MIN`, which the planner reads as an ordered index scan that stops at the first matching row. */
    override val oldestCreatedAtByPartitionsStatus: String =
        """
        SELECT MIN(created_at)
        FROM $tableName
        WHERE partition_no IN (:partitions)
          AND status = :status
        """.toSingleLine()

    /** Selects `MIN` for the reason given on [oldestCreatedAtByPartitionsStatus]. */
    override val oldestDueNonStrict: String =
        """
        SELECT MIN(next_retry_at)
        FROM $tableName
        WHERE partition_no IN (:partitions)
          AND status = :status
          AND next_retry_at <= :now
        """.toSingleLine()
}
