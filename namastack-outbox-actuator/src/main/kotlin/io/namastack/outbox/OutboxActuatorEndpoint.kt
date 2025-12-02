package io.namastack.outbox

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
     * Deletes outbox records for a specific record key and status.
     *
     * @param recordKey The record key to delete records for
     * @param status The status of records to delete
     */
    @DeleteOperation
    fun deleteOutboxRecords(
        @Selector recordKey: String,
        @Selector status: OutboxRecordStatus,
    ) {
        outboxRecordRepository.deleteByRecordKeyAndStatus(recordKey, status)
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
