package io.namastack.outbox.query

import io.namastack.outbox.OutboxRecordStatus

/**
 * SQL contract used by the JDBC record repository, independent of database technology.
 *
 * Statements use named parameters supplied by the repository. Dialects may change execution plans
 * and limit syntax, but must preserve the returned records, retry barriers and per-key ordering by
 * `(created_at, id)`. Bulk reads retain future retries for the processing worker to evaluate.
 * Supported dialects enforce bounded reads in SQL after filtering and ordering. The portable
 * fallback declares [usesJdbcRowLimits] to apply the same bounds through JDBC instead.
 *
 * @author Roland Beisel
 * @since 1.12.0
 */
internal interface JdbcOutboxRecordQueries {
    /** Whether bounded reads require JDBC row limits instead of dialect-specific SQL clauses. */
    val usesJdbcRowLimits: Boolean
        get() = false

    /** Updates every persisted record field by id. */
    val updateRecord: String

    /** Updates processing metadata by id without rewriting payload or context. */
    val updateProcessingState: String

    /** Inserts a record. */
    val insertRecord: String

    /** Loads records of a status in creation-time and id order. */
    fun findByStatus(status: OutboxRecordStatus): String

    /** Loads incomplete records of a key and status in creation-time and id order. */
    val findByKeyAndStatus: String

    /** Loads incomplete records for several keys; each key retains creation-time and id order. */
    val findByKeysAndStatus: String

    /** Loads completed records before a cutoff, oldest completion first. */
    val findByStatusAndCompletedBefore: String

    /** Loads failed records before a retry cutoff, oldest retry first. */
    val findByStatusAndNextRetryBefore: String

    /** Loads ids before a completion cutoff, oldest completion first. */
    val findIdsByStatusAndCompletedBefore: String

    /** Loads ids before a retry cutoff, oldest retry first. */
    val findIdsByStatusAndNextRetryBefore: String

    /** Selects due keys whose first incomplete record is ready; failed and future-retry predecessors block. */
    val findStrictRecordKeys: String

    /** Selects keys with a due record, allowing failed records and future retries to be skipped. */
    val findNonStrictRecordKeys: String

    /** Selects strict keys without a row limit, preserving the legacy zero-batch behavior. */
    val findStrictRecordKeysWithoutLimit: String

    /** Selects non-strict keys without a row limit, preserving the legacy zero-batch behavior. */
    val findNonStrictRecordKeysWithoutLimit: String

    /** Counts all records of a status. */
    val countByStatus: String

    /** Counts records of a partition and status. */
    val countByPartitionStatus: String

    /** Counts records of several partitions and a status. */
    val countByPartitionsStatus: String

    /** Finds the oldest creation time for owned partitions and any requested status. */
    fun oldestCreatedAtByPartitionsStatus(status: OutboxRecordStatus): String

    /** Finds the oldest due retry whose key has no earlier incomplete record. */
    val findOldestStrictDueRetry: String

    /** Finds the oldest due retry without a predecessor barrier. */
    fun findOldestDueRetry(status: OutboxRecordStatus): String

    /** Deletes records of a status. */
    fun deleteByStatus(status: OutboxRecordStatus): String

    /** Deletes records of a key and status. */
    val deleteByKeyAndStatus: String

    /** Deletes one record by id. */
    val deleteById: String

    /** Deletes records with the selected ids. */
    val deleteByIds: String
}
