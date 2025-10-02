package com.beisel.springoutbox

interface OutboxRecordStatusRepository {
    fun countByStatus(status: OutboxRecordStatus): Long
}
