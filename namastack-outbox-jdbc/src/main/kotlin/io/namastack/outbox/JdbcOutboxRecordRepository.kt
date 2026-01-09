package io.namastack.outbox

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.time.OffsetDateTime

/**
 * JDBC implementation of the OutboxRecordRepository and OutboxRecordStatusRepository interfaces.
 *
 * This implementation uses Spring's JdbcTemplate to persist and query outbox records
 * from a relational database.
 *
 * @param jdbcTemplate JDBC template for database operations
 * @param transactionTemplate Transaction template for programmatic transaction management
 * @param recordSerializer Serializer for payload and context
 * @param clock Clock for time-based operations
 *
 * @author Roland Beisel
 * @since 1.1.0
 */
internal open class JdbcOutboxRecordRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val recordSerializer: OutboxPayloadSerializer,
    private val clock: java.time.Clock,
) : OutboxRecordRepository,
    OutboxRecordStatusRepository {
    private val rowMapper =
        RowMapper { rs: ResultSet, _: Int ->
            val recordType = rs.getString("record_type")
            val clazz = resolveClass(recordType)
            val payload = recordSerializer.deserialize(rs.getString("payload"), clazz)

            @Suppress("UNCHECKED_CAST")
            val context =
                rs
                    .getString("context")
                    ?.let { recordSerializer.deserialize(it, Map::class.java as Class<Map<String, String>>) }
                    ?: emptyMap()

            OutboxRecord.restore(
                id = rs.getString("id"),
                recordKey = rs.getString("record_key"),
                payload = payload,
                context = context,
                partition = rs.getInt("partition_no"),
                createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
                status = OutboxRecordStatus.valueOf(rs.getString("status")),
                completedAt = rs.getObject("completed_at", OffsetDateTime::class.java),
                failureCount = rs.getInt("failure_count"),
                failureReason = rs.getString("failure_reason"),
                nextRetryAt = rs.getObject("next_retry_at", OffsetDateTime::class.java),
                handlerId = rs.getString("handler_id"),
                failureException = null,
            )
        }

    override fun <T> save(record: OutboxRecord<T>): OutboxRecord<T> =
        transactionTemplate.execute {
            val payload = record.payload ?: throw IllegalArgumentException("record payload cannot be null")
            val serializedPayload = recordSerializer.serialize(payload)
            val recordType = payload.javaClass.name
            val serializedContext =
                record.context
                    .takeIf { it.isNotEmpty() }
                    ?.let { recordSerializer.serialize(it) }

            // Try UPDATE first within transaction
            val updated =
                jdbcTemplate.update(
                    """
                    UPDATE outbox_record
                    SET status = ?, record_key = ?, record_type = ?, payload = ?, context = ?,
                        partition_no = ?, created_at = ?, completed_at = ?, failure_count = ?,
                        failure_reason = ?, next_retry_at = ?, handler_id = ?
                    WHERE id = ?
                    """.trimIndent(),
                    record.status.name,
                    record.key,
                    recordType,
                    serializedPayload,
                    serializedContext,
                    record.partition,
                    record.createdAt,
                    record.completedAt,
                    record.failureCount,
                    record.failureReason,
                    record.nextRetryAt,
                    record.handlerId,
                    record.id,
                )

            // If no rows updated, INSERT
            if (updated == 0) {
                jdbcTemplate.update(
                    """
                    INSERT INTO outbox_record
                    (id, status, record_key, record_type, payload, context, partition_no,
                     created_at, completed_at, failure_count, failure_reason, next_retry_at, handler_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    record.id,
                    record.status.name,
                    record.key,
                    recordType,
                    serializedPayload,
                    serializedContext,
                    record.partition,
                    record.createdAt,
                    record.completedAt,
                    record.failureCount,
                    record.failureReason,
                    record.nextRetryAt,
                    record.handlerId,
                )
            }
            record
        }!!

    override fun findPendingRecords(): List<OutboxRecord<*>> =
        jdbcTemplate.query(
            """
            SELECT * FROM outbox_record
            WHERE status = ?
            ORDER BY created_at
            """.trimIndent(),
            rowMapper,
            OutboxRecordStatus.NEW.name,
        )

    override fun findCompletedRecords(): List<OutboxRecord<*>> =
        jdbcTemplate.query(
            """
            SELECT * FROM outbox_record
            WHERE status = ?
            ORDER BY created_at
            """.trimIndent(),
            rowMapper,
            OutboxRecordStatus.COMPLETED.name,
        )

    override fun findFailedRecords(): List<OutboxRecord<*>> =
        jdbcTemplate.query(
            """
            SELECT * FROM outbox_record
            WHERE status = ?
            ORDER BY created_at
            """.trimIndent(),
            rowMapper,
            OutboxRecordStatus.FAILED.name,
        )

    override fun findIncompleteRecordsByRecordKey(recordKey: String): List<OutboxRecord<*>> =
        jdbcTemplate.query(
            """
            SELECT * FROM outbox_record
            WHERE record_key = ? AND status = ?
            ORDER BY created_at
            """.trimIndent(),
            rowMapper,
            recordKey,
            OutboxRecordStatus.NEW.name,
        )

    override fun countByStatus(status: OutboxRecordStatus): Long =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM outbox_record WHERE status = ?",
            Long::class.java,
            status.name,
        ) ?: 0L

    override fun countRecordsByPartition(
        partition: Int,
        status: OutboxRecordStatus,
    ): Long =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM outbox_record WHERE partition_no = ? AND status = ?",
            Long::class.java,
            partition,
            status.name,
        ) ?: 0L

    override fun deleteByStatus(status: OutboxRecordStatus) {
        transactionTemplate.execute {
            jdbcTemplate.update(
                "DELETE FROM outbox_record WHERE status = ?",
                status.name,
            )
        }
    }

    override fun deleteByRecordKeyAndStatus(
        recordKey: String,
        status: OutboxRecordStatus,
    ) {
        transactionTemplate.execute {
            jdbcTemplate.update(
                "DELETE FROM outbox_record WHERE record_key = ? AND status = ?",
                recordKey,
                status.name,
            )
        }
    }

    override fun deleteById(id: String) {
        transactionTemplate.executeWithoutResult {
            jdbcTemplate.update(
                "DELETE FROM outbox_record WHERE id = ?",
                id,
            )
        }
    }

    override fun findRecordKeysInPartitions(
        partitions: Set<Int>,
        status: OutboxRecordStatus,
        batchSize: Int,
        ignoreRecordKeysWithPreviousFailure: Boolean,
    ): List<String> {
        val now = OffsetDateTime.now(clock)
        val partitionPlaceholders = partitions.joinToString(",") { "?" }

        val query =
            if (ignoreRecordKeysWithPreviousFailure) {
                """
                SELECT o.record_key
                FROM outbox_record o
                WHERE o.partition_no IN ($partitionPlaceholders)
                  AND o.status = ?
                  AND o.next_retry_at <= ?
                  AND NOT EXISTS (
                    SELECT 1 FROM outbox_record older
                    WHERE older.record_key = o.record_key
                      AND older.completed_at IS NULL
                      AND older.created_at < o.created_at
                  )
                GROUP BY o.record_key
                ORDER BY MIN(o.created_at) ASC
                LIMIT ?
                """.trimIndent()
            } else {
                """
                SELECT o.record_key
                FROM outbox_record o
                WHERE o.partition_no IN ($partitionPlaceholders)
                  AND o.status = ?
                  AND o.next_retry_at <= ?
                GROUP BY o.record_key
                ORDER BY MIN(o.created_at) ASC
                LIMIT ?
                """.trimIndent()
            }

        val args = mutableListOf<Any>()
        args.addAll(partitions)
        args.add(status.name)
        args.add(now)
        args.add(batchSize)

        return jdbcTemplate.queryForList(query, String::class.java, *args.toTypedArray())
    }

    private fun resolveClass(className: String): Class<*> =
        try {
            Thread.currentThread().contextClassLoader.loadClass(className)
        } catch (ex: ClassNotFoundException) {
            throw IllegalStateException("Cannot find class for record type $className", ex)
        }
}
