package io.namastack.springoutbox

/**
 * Repository interface for querying outbox record status statistics.
 *
 * Provides methods for retrieving metrics and counts based on outbox record status.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
interface OutboxRecordStatusRepository {
    /**
     * Counts the number of outbox records with the specified status.
     *
     * @param status The status to count records for
     * @return The number of records with the given status
     */
    fun countByStatus(status: OutboxRecordStatus): Long
}
