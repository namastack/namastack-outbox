package io.namastack.outbox.query

/**
 * SQL statements used by the JDBC record repository for one resolved record table.
 *
 * Every implementation must return the same rows in the same order: dialects may change the execution
 * plan and the row-limit syntax, but not the result. Records of one key are ordered by
 * `(created_at, id)`, strict key selection treats every earlier incomplete record of a key as a barrier,
 * and bulk reads keep records with a future retry time so the worker can evaluate them. The repository
 * additionally limits every bounded read with `withMaxRows`.
 *
 * Named parameters: `id`, `ids`, `partitions`, `partitionNo`, `status`, `statuses`, `now`, `recordKey`,
 * `recordKeys`, `cutoff`, `limit` and the record columns of [insertRecord] and [updateRecord].
 * Statements that take `limit` may ignore it; binding it is always safe.
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

    /** Selects records of a key and status in creation-time and id order. */
    val findByKeyAndStatus: String

    /** Selects the incomplete records of several keys and a status; records of one key keep creation-time and id order. */
    val findByKeysAndStatus: String

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
     * record of each key. `FAILED` records and records not yet due do not block a key.
     */
    val findNonStrictRecordKeys: String

    /** Counts records of a status. */
    val countByStatus: String

    /** Counts records grouped by status for several statuses. */
    val countByStatuses: String

    /** Counts records of one partition and a status. */
    val countByPartitionStatus: String

    /** Counts records of several partitions and a status. */
    val countByPartitionsStatus: String

    /** Selects the earliest creation time of incomplete records of a status in several partitions; at most one row. */
    val oldestCreatedAtByPartitionsStatus: String

    /** Selects the earliest next retry time of due records that are the first incomplete record of their key; at most one row. */
    val findOldestStrictDueRetry: String

    /** Selects the earliest next retry time of due records; at most one row. */
    val findOldestDueRetry: String

    /** Deletes records of a status. */
    val deleteByStatus: String

    /** Deletes records of a key and status. */
    val deleteByKeyAndStatus: String

    /** Deletes one record by id. */
    val deleteById: String

    /** Deletes records by ids. */
    val deleteByIds: String
}
