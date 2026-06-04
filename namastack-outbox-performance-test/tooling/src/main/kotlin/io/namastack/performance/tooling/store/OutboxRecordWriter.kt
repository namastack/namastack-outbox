package io.namastack.performance.tooling.store

import io.namastack.performance.tooling.record.PerformanceRecord

internal interface OutboxRecordWriter : AutoCloseable {
    fun append(records: List<PerformanceRecord>)
}
