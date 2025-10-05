package io.namastack.springoutbox

interface OutboxRecordStatusRepository {
    fun countByStatus(status: OutboxRecordStatus): Long
}
