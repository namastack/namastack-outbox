package io.namastack.outbox

import io.namastack.outbox.config.JdbcDatabaseType

/**
 * SQL statements of [JdbcOutboxRecordRepository] for one resolved record table.
 *
 * The base class holds portable SQL without a row-limit clause; the repository additionally limits
 * every bounded query with `withMaxRows`, so the portable statements return the same rows as the
 * dialect-specific ones. Dialect subclasses add a real SQL row limit and adapt the statements to
 * their planner. Every variant returns the same rows in the same order.
 *
 * The record-key, oldest-due and oldest-created statements rely on the invariant that a record is
 * `NEW` or `FAILED` exactly when its `completed_at` is `NULL`.
 *
 * Named parameters: `partitions`, `status`, `now`, `statuses`, `recordKeys`, `cutoff` and `limit`.
 * Statements that take `limit` may ignore it (portable variant); binding it is always safe.
 *
 * @param tableName Resolved name of the record table
 * @param limitClause Builds the trailing row-limit clause for a named parameter, or an empty string
 *
 * @author Aleksander Zamojski
 * @since 1.12.0
 */
internal open class JdbcOutboxRecordQueries(
    protected val tableName: String,
    private val limitClause: (parameter: String) -> String = { "" },
) {
    /** Updates all columns of an existing record. */
    val updateRecord: String =
        """
        UPDATE $tableName
        SET status = :status, record_key = :recordKey, record_type = :recordType, payload = :payload, context = :context,
            partition_no = :partitionNo, created_at = :createdAt, completed_at = :completedAt, failure_count = :failureCount,
            failure_reason = :failureReason, next_retry_at = :nextRetryAt, handler_id = :handlerId
        WHERE id = :id
        """.toSingleLine()

    /** Updates the processing state of an existing record. */
    val updateProcessingState: String =
        """
        UPDATE $tableName
        SET status = :status, completed_at = :completedAt, failure_count = :failureCount,
            failure_reason = :failureReason, next_retry_at = :nextRetryAt
        WHERE id = :id
        """.toSingleLine()

    /** Inserts a new record. */
    val insertRecord: String =
        """
        INSERT INTO $tableName
        (id, status, record_key, record_type, payload, context, partition_no,
         created_at, completed_at, failure_count, failure_reason, next_retry_at, handler_id)
        VALUES (:id, :status, :recordKey, :recordType, :payload, :context, :partitionNo,
         :createdAt, :completedAt, :failureCount, :failureReason, :nextRetryAt, :handlerId)
        """.toSingleLine()

    /** Selects records by status ordered by creation time and id. */
    val findByStatus: String =
        """
        SELECT *
        FROM $tableName
        WHERE status = :status
        ORDER BY created_at, id
        """.toSingleLine()

    /** Selects records by record key and status ordered by creation time and id. */
    val findByKeyAndStatus: String =
        """
        SELECT *
        FROM $tableName
        WHERE record_key = :recordKey
          AND status = :status
        ORDER BY created_at, id
        """.toSingleLine()

    /**
     * Selects the incomplete records of several keys with the given status. Rows of one key are
     * returned in creation-time and id order; the order between keys is not significant.
     */
    open val findByKeysAndStatus: String =
        """
        SELECT *
        FROM $tableName
        WHERE record_key IN (:recordKeys)
          AND status = :status
          AND completed_at IS NULL
        ORDER BY record_key, completed_at, created_at, id
        """.toSingleLine()

    /** Selects at most `limit` records by status completed before a cutoff, oldest first. */
    val findByStatusAndCompletedBefore: String =
        """
        SELECT *
        FROM $tableName
        WHERE status = :status
          AND completed_at < :cutoff
        ORDER BY completed_at
        ${limitClause("limit")}
        """.toSingleLine()

    /** Selects at most `limit` records by status with the next retry time before a cutoff, oldest first. */
    val findByStatusAndNextRetryBefore: String =
        """
        SELECT *
        FROM $tableName
        WHERE status = :status
          AND next_retry_at < :cutoff
        ORDER BY next_retry_at
        ${limitClause("limit")}
        """.toSingleLine()

    /** Selects at most `limit` ids by status completed before a cutoff, oldest first. */
    val findIdsByStatusAndCompletedBefore: String =
        """
        SELECT id
        FROM $tableName
        WHERE status = :status
          AND completed_at < :cutoff
        ORDER BY completed_at
        ${limitClause("limit")}
        """.toSingleLine()

    /** Selects at most `limit` ids by status with the next retry time before a cutoff, oldest first. */
    val findIdsByStatusAndNextRetryBefore: String =
        """
        SELECT id
        FROM $tableName
        WHERE status = :status
          AND next_retry_at < :cutoff
        ORDER BY next_retry_at
        ${limitClause("limit")}
        """.toSingleLine()

    /**
     * Selects at most `limit` keys whose first incomplete record is due, ordered by that record's
     * creation time and id. A key has at most one such head record, so no grouping is needed.
     *
     * Any earlier incomplete record of the key (`NEW` or `FAILED`, due or not) blocks a record.
     * `o.completed_at IS NULL` lets the incomplete-record index serve the outer scan in
     * `(created_at, id)` order, so the row limit stops it early. `earlier.created_at <= o.created_at`
     * turns the predecessor check into an index range on the record-key index.
     */
    open val recordKeysWithPreviousFailureFilter: String =
        """
        SELECT o.record_key
        FROM $tableName o
        WHERE o.partition_no IN (:partitions)
          AND o.status = :status
          AND o.next_retry_at <= :now
          AND o.completed_at IS NULL
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
        ORDER BY o.created_at, o.id
        ${limitClause("limit")}
        """.toSingleLine()

    /**
     * Selects at most `limit` keys with a due record, ordered by the creation time and id of the
     * first due record of each key.
     *
     * Only an earlier due record with the same status blocks a record, so the predecessor check
     * repeats the outer status and due conditions; `FAILED` and not yet due records do not block.
     */
    open val recordKeysWithoutPreviousFailureFilter: String =
        """
        SELECT o.record_key
        FROM $tableName o
        WHERE o.partition_no IN (:partitions)
          AND o.status = :status
          AND o.next_retry_at <= :now
          AND o.completed_at IS NULL
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
        ORDER BY o.created_at, o.id
        ${limitClause("limit")}
        """.toSingleLine()

    /** Counts records by status. */
    val countByStatus: String =
        """
        SELECT COUNT(*)
        FROM $tableName
        WHERE status = :status
        """.toSingleLine()

    /** Counts records grouped by status for the given statuses. */
    val countByStatuses: String =
        """
        SELECT status, COUNT(*)
        FROM $tableName
        WHERE status IN (:statuses)
        GROUP BY status
        """.toSingleLine()

    /** Counts records of one partition by status. */
    val countByPartitionStatus: String =
        """
        SELECT COUNT(*)
        FROM $tableName
        WHERE partition_no = :partitionNo
          AND status = :status
        """.toSingleLine()

    /** Counts records across partitions by status. */
    val countByPartitionsStatus: String =
        """
        SELECT COUNT(*)
        FROM $tableName
        WHERE partition_no IN (:partitions)
          AND status = :status
        """.toSingleLine()

    /**
     * Selects the earliest creation time of incomplete records with the given status. Returns at most
     * one row; no row maps to `null`.
     *
     * The ordered single-row read lets planners that do not turn `MIN` into an index read (MySQL,
     * MariaDB) stop at the first matching index entry.
     */
    val oldestCreatedAtByPartitionsStatus: String =
        """
        SELECT created_at
        FROM $tableName
        WHERE partition_no IN (:partitions)
          AND status = :status
          AND completed_at IS NULL
        ORDER BY created_at
        ${limitClause("limit")}
        """.toSingleLine()

    /**
     * Selects the earliest next retry time of due records that are the first incomplete record of
     * their key. Returns at most one row; no row maps to `null`.
     *
     * The outer scan reads `(status, next_retry_at)` in retry order and stops at the first record that
     * passes the predecessor check, so it has no `o.completed_at IS NULL` condition.
     */
    open val oldestDueNextRetryAtWithPreviousFailureFilter: String =
        """
        SELECT o.next_retry_at
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
        ORDER BY o.next_retry_at
        ${limitClause("limit")}
        """.toSingleLine()

    /** Selects the earliest next retry time of due records. */
    val oldestDueNextRetryAtWithoutPreviousFailureFilter: String =
        """
        SELECT MIN(o.next_retry_at)
        FROM $tableName o
        WHERE o.partition_no IN (:partitions)
          AND o.status = :status
          AND o.next_retry_at <= :now
        """.toSingleLine()

    /** Deletes records by status. */
    val deleteByStatus: String =
        """
        DELETE FROM $tableName
        WHERE status = :status
        """.toSingleLine()

    /** Deletes records by record key and status. */
    val deleteByKeyAndStatus: String =
        """
        DELETE FROM $tableName
        WHERE record_key = :recordKey
          AND status = :status
        """.toSingleLine()

    /** Deletes one record by id. */
    val deleteById: String =
        """
        DELETE FROM $tableName
        WHERE id = :id
        """.toSingleLine()

    /** Deletes records by ids. */
    val deleteByIds: String =
        """
        DELETE FROM $tableName
        WHERE id IN (:ids)
        """.toSingleLine()

    companion object {
        /**
         * Returns the statements for the given database type, or the portable statements when the
         * type is unknown.
         *
         * @param databaseType Resolved database type, or `null` if the product is not supported
         * @param tableName Resolved name of the record table
         * @return Statements for the database type
         */
        fun of(
            databaseType: JdbcDatabaseType?,
            tableName: String,
        ): JdbcOutboxRecordQueries =
            when (databaseType) {
                JdbcDatabaseType.PostgreSQL -> PostgresJdbcOutboxRecordQueries(tableName)
                JdbcDatabaseType.MySQL, JdbcDatabaseType.MariaDB -> MySqlJdbcOutboxRecordQueries(tableName)
                JdbcDatabaseType.SQLServer -> SqlServerJdbcOutboxRecordQueries(tableName)
                JdbcDatabaseType.Oracle -> OracleJdbcOutboxRecordQueries(tableName)
                JdbcDatabaseType.H2 -> H2JdbcOutboxRecordQueries(tableName)
                null -> JdbcOutboxRecordQueries(tableName)
            }
    }
}

/**
 * H2 statements: the standard statements with `LIMIT`.
 *
 * @author Aleksander Zamojski
 * @since 1.12.0
 */
internal class H2JdbcOutboxRecordQueries(
    tableName: String,
) : JdbcOutboxRecordQueries(tableName, { "LIMIT :$it" })

/**
 * PostgreSQL statements: `LIMIT`, and the bulk record load as one ordered index probe per key.
 *
 * @author Aleksander Zamojski
 * @since 1.12.0
 */
internal class PostgresJdbcOutboxRecordQueries(
    tableName: String,
) : JdbcOutboxRecordQueries(tableName, { "LIMIT :$it" }) {
    override val findByKeysAndStatus: String =
        """
        SELECT o.*
        FROM (
          SELECT t.k
          FROM unnest(CAST(ARRAY[:recordKeys] AS varchar[])) AS t(k)
          ORDER BY t.k
        ) k
        CROSS JOIN LATERAL (
          SELECT r.*
          FROM $tableName r
          WHERE r.record_key = k.k
            AND r.status = :status
            AND r.completed_at IS NULL
          ORDER BY r.completed_at, r.created_at, r.id
        ) o
        """.toSingleLine()
}

/**
 * MySQL and MariaDB statements: the standard statements with `LIMIT`.
 *
 * @author Aleksander Zamojski
 * @since 1.12.0
 */
internal class MySqlJdbcOutboxRecordQueries(
    tableName: String,
) : JdbcOutboxRecordQueries(tableName, { "LIMIT :$it" })

/**
 * SQL Server statements: `OFFSET 0 ROWS FETCH NEXT ... ROWS ONLY`. The record-key query without the
 * previous-failure filter keeps the grouped form, which the SQL Server planner handles better.
 *
 * @author Aleksander Zamojski
 * @since 1.12.0
 */
internal class SqlServerJdbcOutboxRecordQueries(
    tableName: String,
) : JdbcOutboxRecordQueries(tableName, { "OFFSET 0 ROWS FETCH NEXT :$it ROWS ONLY" }) {
    override val recordKeysWithoutPreviousFailureFilter: String =
        """
        SELECT o.record_key
        FROM $tableName o
        WHERE o.partition_no IN (:partitions)
          AND o.status = :status
          AND o.next_retry_at <= :now
        GROUP BY o.record_key
        ORDER BY MIN(o.created_at) ASC
        """.toSingleLine()
}

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
) : JdbcOutboxRecordQueries(tableName, { "FETCH FIRST :$it ROWS ONLY" }) {
    /**
     * The outer scan uses the status-leading index, so it has no `o.completed_at IS NULL` condition, and
     * orders by `SYS_EXTRACT_UTC(o.created_at)` because that is the order of the index entries.
     */
    override val recordKeysWithPreviousFailureFilter: String =
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
     * Like [recordKeysWithPreviousFailureFilter], with the predecessor check limited to earlier due
     * records with the same status.
     */
    override val recordKeysWithoutPreviousFailureFilter: String =
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
    override val oldestDueNextRetryAtWithPreviousFailureFilter: String =
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
