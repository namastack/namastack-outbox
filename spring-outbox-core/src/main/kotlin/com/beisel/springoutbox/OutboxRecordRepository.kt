package com.beisel.springoutbox

interface OutboxRecordRepository {
    fun save(record: OutboxRecord): OutboxRecord

    fun findPendingRecords(): List<OutboxRecord>

    fun findCompletedRecords(): List<OutboxRecord>

    fun findFailedRecords(): List<OutboxRecord>

    fun findAggregateIdsWithPendingRecords(status: OutboxRecordStatus): List<String>

    fun findAggregateIdsWithFailedRecords(): List<String>

    fun findAllIncompleteRecordsByAggregateId(aggregateId: String): List<OutboxRecord>

    fun deleteByStatus(status: OutboxRecordStatus)

    fun deleteByAggregateIdAndStatus(
        aggregateId: String,
        status: OutboxRecordStatus,
    )
}
