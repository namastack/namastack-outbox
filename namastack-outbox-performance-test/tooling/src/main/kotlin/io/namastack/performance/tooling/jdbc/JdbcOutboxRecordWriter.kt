package io.namastack.performance.tooling.jdbc

import io.namastack.performance.tooling.record.PerformanceRecord
import io.namastack.performance.tooling.store.OutboxRecordWriter
import java.sql.Connection
import java.sql.PreparedStatement

internal class JdbcOutboxRecordWriter(
    private val connection: Connection,
) : OutboxRecordWriter {
    private val statement: PreparedStatement

    init {
        connection.autoCommit = false
        statement =
            connection.prepareStatement(
                """
                INSERT INTO outbox_record
                (id, status, record_key, record_type, payload, context, created_at, completed_at,
                 failure_count, failure_reason, next_retry_at, partition_no, handler_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            )
    }

    override fun append(records: List<PerformanceRecord>) {
        records.forEach { statement.bind(it) }
        statement.executeBatch()
        statement.clearBatch()
        connection.commit()
    }

    override fun close() {
        statement.close()
        connection.close()
    }
}
