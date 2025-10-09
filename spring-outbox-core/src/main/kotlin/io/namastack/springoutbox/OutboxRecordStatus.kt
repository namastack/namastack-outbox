package io.namastack.springoutbox

/**
 * Enumeration representing the possible statuses of an outbox record.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
enum class OutboxRecordStatus {
    /**
     * Record is newly created and ready for processing.
     */
    NEW,

    /**
     * Record has been successfully processed and completed.
     */
    COMPLETED,

    /**
     * Record processing has permanently failed after retries.
     */
    FAILED,
}
