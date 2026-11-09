package io.namastack.outbox.query

/**
 * SQL statements used by the JDBC record repository for one resolved record table.
 *
 * Every implementation must return the same rows in the same order: a dialect may change the execution
 * plan and the row-limit syntax, but not the result. Records of one key are ordered by `(created_at, id)`,
 * strict key selection treats every earlier incomplete record of a key as a barrier, and bulk reads keep
 * records with a future retry time so the worker can evaluate them. The order between keys in a bulk read
 * is not significant.
 *
 * The statements rely on the invariant that `completed_at` is `NULL` exactly for `NEW` and `FAILED` records.
 *
 * Statements that take `limit` may render no row-limit clause, so the repository also caps every bounded
 * read with `withMaxRows`. Binding `limit` to a statement that does not use it is safe.
 *
 * @author Aleksander Zamojski
 * @since 1.12.0
 */
internal interface JdbcOutboxRecordQueries {
    /** Updates every column of a record by id. */
    val updateRecord: String

    /** Updates the processing state of a record by id without writing payload or context. */
    val updateProcessingState: String

    /** Inserts a record. */
    val insertRecord: String

    /** Selects records of a status in creation-time and id order. */
    val findByStatus: String

    /** Selects incomplete records of a key and status in creation-time and id order. */
    val findIncompleteByKeyAndStatus: String

    /** Selects incomplete records of several keys and a status; records of one key keep creation-time and id order. */
    val findIncompleteByKeysAndStatus: String

    /** Selects at most `limit` records of a status completed before a cutoff, oldest completion first. */
    val findByStatusAndCompletedBefore: String

    /** Selects at most `limit` records of a status whose next retry time is before a cutoff, oldest first. */
    val findByStatusAndNextRetryBefore: String

    /** Selects at most `limit` ids of a status completed before a cutoff, oldest completion first. */
    val findIdsByStatusAndCompletedBefore: String

    /** Selects at most `limit` ids of a status whose next retry time is before a cutoff, oldest first. */
    val findIdsByStatusAndNextRetryBefore: String

    /**
     * Selects at most `limit` keys whose first incomplete record is due, ordered by that record's creation
     * time and id. Any earlier incomplete record of the key (`NEW` or `FAILED`, due or not) blocks the key.
     */
    val findStrictRecordKeys: String

    /**
     * Selects at most `limit` keys with a due record, ordered by the creation time and id of the first due
     * record of each key. Records with another status and records not yet due do not block a key.
     */
    val findNonStrictRecordKeys: String

    /** Counts records of a status. */
    val countByStatus: String

    /** Counts records grouped by status; binds `statuses`. Returns one status and count row per status that has records. */
    val countByStatuses: String

    /** Counts records of one partition and a status. */
    val countByPartitionStatus: String

    /** Counts the records of one status across several partitions; binds `partitions` and `status`. Returns one row. */
    val countByPartitionsStatus: String

    /**
     * Selects the earliest creation time of incomplete records of a status in several partitions; at most `limit`
     * rows. A dialect may instead select one aggregate row, `NULL` if none match.
     */
    val oldestCreatedAtByPartitionsStatus: String

    /** Selects the earliest next retry time of due records that are the first incomplete record of their key; at most `limit` rows. */
    val oldestDueStrict: String

    /**
     * Selects the earliest next retry time of due incomplete records; at most `limit` rows. A dialect may instead
     * select one aggregate row, `NULL` if none match.
     */
    val oldestDueNonStrict: String

    /** Deletes records of a status. */
    val deleteByStatus: String

    /** Deletes records of a key and status. */
    val deleteByKeyAndStatus: String

    /** Deletes one record by id. */
    val deleteById: String

    /** Deletes records by ids. */
    val deleteByIds: String
}
