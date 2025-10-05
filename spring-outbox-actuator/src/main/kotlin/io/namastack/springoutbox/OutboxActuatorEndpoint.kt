package io.namastack.springoutbox

import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.Selector

@Endpoint(id = "outbox")
class OutboxActuatorEndpoint(
    private val outboxRecordRepository: OutboxRecordRepository,
) {
    @DeleteOperation
    fun deleteOutboxRecords(
        @Selector aggregateId: String,
        @Selector status: OutboxRecordStatus,
    ) {
        outboxRecordRepository.deleteByAggregateIdAndStatus(aggregateId, status)
    }

    @DeleteOperation
    fun deleteAllOutboxRecords(
        @Selector status: OutboxRecordStatus,
    ) {
        outboxRecordRepository.deleteByStatus(status)
    }
}
