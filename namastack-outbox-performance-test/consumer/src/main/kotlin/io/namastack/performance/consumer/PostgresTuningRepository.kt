package io.namastack.performance.consumer

import io.namastack.outbox.JdbcOutboxRecordEntity
import io.namastack.outbox.JdbcOutboxRecordEntityMapper
import io.namastack.outbox.JdbcOutboxRecordEntityRowMapper
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.OutboxRecordStatus
import io.namastack.outbox.OutboxRecordStatusRepository
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock

/** PostgreSQL-only experimental adapter. Winners are promoted to the library after comparison. */
internal class PostgresTuningRepository(
    private val delegate: OutboxRecordRepository,
    statusDelegate: OutboxRecordStatusRepository,
    private val jdbc: JdbcClient,
    private val mapper: JdbcOutboxRecordEntityMapper,
    private val transactions: TransactionTemplate,
    private val clock: Clock,
    private val tuning: TuningSettings,
    private val completionBatcher: CompletionBatcher? = null,
) : OutboxRecordRepository by delegate,
    OutboxRecordStatusRepository by statusDelegate {
    private val rowMapper = JdbcOutboxRecordEntityRowMapper()

    @Volatile
    private var prefetched: Map<String, List<JdbcOutboxRecordEntity>> = emptyMap()

    override fun findRecordKeysInPartitions(
        partitions: Set<Int>,
        status: OutboxRecordStatus,
        batchSize: Int,
        ignoreRecordKeysWithPreviousFailure: Boolean,
    ): List<String> {
        if (partitions.isEmpty()) return emptyList()
        val headLimit = tuning.headLimitKeys && ignoreRecordKeysWithPreviousFailure && status == OutboxRecordStatus.NEW
        val inline = tuning.inlineStatus || headLimit
        val statusPredicate = if (inline) "'${status.name}'" else ":status"
        val predecessor = if (tuning.tuplePredecessor) {
            "(older.created_at, older.id) < (o.created_at, o.id)"
        } else {
            "(older.created_at < o.created_at OR (older.created_at = o.created_at AND older.id < o.id))"
        }
        val barrier =
            if (ignoreRecordKeysWithPreviousFailure) {
                """
            AND NOT EXISTS (
              SELECT 1 FROM outbox_record older WHERE older.record_key = o.record_key
              AND older.completed_at IS NULL
              AND $predecessor
            )
        """
            } else {
                ""
            }
        val sql = if (headLimit) {
            // A valid pending key has one incomplete head. Keep the outer grouping as a
            // uniqueness guard even for inconsistent NEW rows with a completion timestamp.
            """
            SELECT record_key FROM (
                SELECT o.record_key, o.created_at FROM outbox_record o
                WHERE o.partition_no IN (:partitions) AND o.status = 'NEW' AND o.next_retry_at <= :now
                $barrier ORDER BY o.created_at, o.id LIMIT :limit
            ) heads GROUP BY record_key ORDER BY MIN(created_at)
            """
        } else {
            """
            SELECT o.record_key FROM outbox_record o
            WHERE o.partition_no IN (:partitions) AND o.status = $statusPredicate AND o.next_retry_at <= :now
            $barrier GROUP BY o.record_key ORDER BY MIN(o.created_at)
        """ + if (tuning.sqlLimit) " LIMIT :limit" else ""
        }
        val query = jdbc.sql(sql).param("partitions", partitions.toList()).param("now", Timestamp.from(clock.instant()))
        if (!inline) query.param("status", status.name)
        if (tuning.sqlLimit || headLimit) query.param("limit", batchSize) else query.withMaxRows(batchSize)
        val keys = query.query(String::class.java).list().filterNotNull()
        prefetched =
            if (tuning.bulkFetchSize > 0) {
                val rows = keys.chunked(tuning.bulkFetchSize).flatMap { fetchRaw(it) }.groupBy { it.recordKey }
                keys.associateWith { rows[it].orEmpty() }
            } else {
                emptyMap()
            }
        return keys
    }

    override fun findIncompleteRecordsByRecordKey(recordKey: String): List<OutboxRecord<*>> {
        if (tuning.bulkFetchSize == 0 && tuning.maxRecordsPerKey == 0 && !tuning.lazyDeserialization) {
            return delegate.findIncompleteRecordsByRecordKey(recordKey)
        }
        val raw = prefetched[recordKey] ?: fetchRaw(listOf(recordKey))
        if (!tuning.lazyDeserialization) return raw.map(mapper::map)
        // Strict-order processing must never decode a future retry or anything after it.
        val now = clock.instant()
        val eligible =
            if (tuning.stopOnFirstFailure) {
                raw.takeWhile { !it.nextRetryAt.isAfter(now) }
            } else {
                raw.filter { !it.nextRetryAt.isAfter(now) }
            }
        return object : AbstractList<OutboxRecord<*>>() {
            override val size: Int get() = eligible.size

            override fun get(index: Int): OutboxRecord<*> = mapper.map(eligible[index])
        }
    }

    private fun fetchRaw(keys: List<String>): List<JdbcOutboxRecordEntity> {
        if (keys.isEmpty()) return emptyList()
        val ordered =
            "SELECT * FROM outbox_record WHERE record_key IN (:keys) AND status = 'NEW' " +
                "ORDER BY record_key, created_at, id"
        val bounded = """
            SELECT * FROM (
                SELECT r.*, ROW_NUMBER() OVER (PARTITION BY record_key ORDER BY created_at, id) AS key_row
                FROM outbox_record r WHERE record_key IN (:keys) AND status = 'NEW'
            ) bounded WHERE key_row <= :maxRecords ORDER BY record_key, created_at, id
        """
        val query = jdbc.sql(if (tuning.maxRecordsPerKey > 0) bounded else ordered).param("keys", keys)
        if (tuning.maxRecordsPerKey > 0) query.param("maxRecords", tuning.maxRecordsPerKey)
        return query.query(rowMapper).list().filterNotNull()
    }

    override fun updateCompletion(record: OutboxRecord<*>): Boolean {
        if (completionBatcher != null &&
            !org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()
        ) {
            return completionBatcher.complete(record)
        }
        if (!tuning.autocommitCompletion) return delegate.updateCompletion(record)
        return jdbc
            .sql(
                """
            UPDATE outbox_record SET status = :status, completed_at = :completed,
                failure_count = :failures, failure_reason = :reason, next_retry_at = :retry WHERE id = :id
        """,
            ).param("status", record.status.name)
            .param("completed", record.completedAt?.let(Timestamp::from))
            .param("failures", record.failureCount)
            .param("reason", record.failureReason)
            .param("retry", Timestamp.from(record.nextRetryAt))
            .param("id", record.id)
            .update() == 1
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> withRecordKeyProcessing(
        recordKey: String,
        action: () -> T,
    ): T =
        if (tuning.transactionPerKey) {
            transactions.execute { action() } as T
        } else {
            delegate.withRecordKeyProcessing(recordKey, action)
        }
}

internal data class TuningSettings(
    val sqlLimit: Boolean = false,
    val headLimitKeys: Boolean = false,
    val tuplePredecessor: Boolean = false,
    val inlineStatus: Boolean = false,
    val bulkFetchSize: Int = 0,
    val maxRecordsPerKey: Int = 0,
    val lazyDeserialization: Boolean = false,
    val autocommitCompletion: Boolean = false,
    val transactionPerKey: Boolean = false,
    val stopOnFirstFailure: Boolean = true,
    val completionBatchSize: Int = 0,
    val completionBatchWaitMicros: Long = 250,
) {
    init {
        require(bulkFetchSize >= 0 && maxRecordsPerKey >= 0)
        require(completionBatchSize in 0..512 && completionBatchWaitMicros >= 0)
        require(!transactionPerKey || completionBatchSize == 0) {
            "Per-key transactions and cross-key batching are separate experiment modes"
        }
    }

    companion object {
        fun fromEnvironment() =
            TuningSettings(
                sqlLimit = System.getenv("PERF_SQL_LIMIT") == "true",
                headLimitKeys = System.getenv("PERF_HEAD_LIMIT_KEYS") == "true",
                tuplePredecessor = System.getenv("PERF_TUPLE_PREDECESSOR") == "true",
                inlineStatus = System.getenv("PERF_INLINE_STATUS") == "true",
                bulkFetchSize = System.getenv("PERF_BULK_FETCH_SIZE")?.toInt() ?: 0,
                maxRecordsPerKey = System.getenv("PERF_MAX_RECORDS_PER_KEY")?.toInt() ?: 0,
                lazyDeserialization = System.getenv("PERF_LAZY_DESERIALIZATION") == "true",
                autocommitCompletion = System.getenv("PERF_AUTOCOMMIT_COMPLETION") == "true",
                transactionPerKey = System.getenv("PERF_TRANSACTION_PER_KEY") == "true",
                completionBatchSize = System.getenv("PERF_COMPLETION_BATCH_SIZE")?.toInt() ?: 0,
                completionBatchWaitMicros = System.getenv("PERF_COMPLETION_BATCH_WAIT_US")?.toLong() ?: 250,
            )
    }
}
