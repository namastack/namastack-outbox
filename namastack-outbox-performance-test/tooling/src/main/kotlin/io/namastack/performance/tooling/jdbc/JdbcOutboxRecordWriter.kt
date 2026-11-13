package io.namastack.performance.tooling.jdbc

import io.namastack.performance.tooling.store.OutboxRecordWriter
import io.namastack.performance.tooling.record.PerformanceRecord
import java.sql.Connection
import java.sql.PreparedStatement

internal class JdbcOutboxRecordWriter(
    private val connection: Connection,
) : OutboxRecordWriter {
    private val statement: PreparedStatement

    init {
        connection.autoCommit = false
        statement = connection.prepareStatement(JdbcPerformanceSql.insertOutboxRecord)
    }

    override fun append(records: List<PerformanceRecord>) {
        records.forEach { statement.bind(it) }
        statement.executeBatch()
        connection.commit()
    }

    override fun close() {
        statement.close()
        connection.close()
    }
}
