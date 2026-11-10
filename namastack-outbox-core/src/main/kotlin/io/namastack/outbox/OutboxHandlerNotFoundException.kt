package io.namastack.outbox

import io.namastack.outbox.processing.OutboxRecordKey

/**
 * Signals that an outbox record references a handler that is unavailable on this instance.
 *
 * @param recordId ID of the affected outbox record
 * @param recordKey Stored key of the affected outbox record; may encode a processing group
 * @param handlerId ID of the unavailable handler
 * @author Roland Beisel
 * @since 1.10.0
 */
class OutboxHandlerNotFoundException(
    val recordId: String,
    val recordKey: String,
    val handlerId: String,
) : IllegalStateException() {
    /** Original business key of the record whose handler is unavailable. */
    val businessKey: String

    /** Persisted processing group of the affected record. */
    val processingGroup: String

    init {
        val decodedKey = OutboxRecordKey.decode(recordKey)
        businessKey = decodedKey.businessKey
        processingGroup = decodedKey.processingGroup
    }

    override val message: String =
        "No handler with id $handlerId for outbox record $recordId " +
            "(businessKey=$businessKey, processingGroup=$processingGroup, storageKey=$recordKey)"
}
