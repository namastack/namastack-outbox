package io.namastack.performance.tooling.jdbc

import io.namastack.performance.tooling.record.PerformanceRecord
import java.sql.PreparedStatement
import java.sql.Timestamp

internal fun PreparedStatement.bind(record: PerformanceRecord) {
    setString(1, record.id)
    setString(2, record.status)
    setString(3, record.recordKey)
    setString(4, record.recordType)
    setString(5, record.payload)
    setString(6, record.context)
    setTimestamp(7, Timestamp.from(record.createdAt))
    setTimestamp(8, record.completedAt?.let(Timestamp::from))
    setInt(9, record.failureCount)
    setString(10, record.failureReason)
    setTimestamp(11, Timestamp.from(record.nextRetryAt))
    setInt(12, record.partition)
    setString(13, record.handlerId)
    addBatch()
}
