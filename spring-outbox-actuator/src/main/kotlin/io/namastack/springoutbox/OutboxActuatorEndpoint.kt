package io.namastack.springoutbox

import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.Selector

/**
 * Spring Boot Actuator endpoint for managing outbox records.
 *
 * This endpoint provides HTTP operations for administrative tasks
 * such as cleaning up completed or failed outbox records.
 *
 * @param outboxRecordRepository Repository for accessing outbox records
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@Endpoint(id = "outbox")
class OutboxActuatorEndpoint(
    private val outboxRecordRepository: OutboxRecordRepository,
) {
    /**
     * Deletes outbox records for a specific aggregate ID and status.
     *
     * @param aggregateId The aggregate ID to delete records for
     * @param status The status of records to delete
     */
    @DeleteOperation
    fun deleteOutboxRecords(
        @Selector aggregateId: String,
        @Selector status: OutboxRecordStatus,
    ) {
        outboxRecordRepository.deleteByAggregateIdAndStatus(aggregateId, status)
    }

    /**
     * Deletes all outbox records with the specified status.
     *
     * @param status The status of records to delete
     */
    @DeleteOperation
    fun deleteAllOutboxRecords(
        @Selector status: OutboxRecordStatus,
    ) {
        outboxRecordRepository.deleteByStatus(status)
    }
}
