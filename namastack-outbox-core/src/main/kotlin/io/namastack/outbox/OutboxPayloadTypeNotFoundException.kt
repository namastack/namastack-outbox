package io.namastack.outbox

import io.namastack.outbox.processing.OutboxRecordKey

/**
 * Indicates that an outbox record cannot be materialized because its payload type is unavailable.
 *
 * @param recordId ID of the affected outbox record
 * @param recordKey Stored key of the affected outbox record, including an encoded processing group
 * @param payloadType Fully qualified name of the unavailable payload type
 * @param handlerId ID of the handler referenced by the affected record
 * @param cause Class-loading failure that prevented materialization
 * @author Roland Beisel
 * @since 1.10.0
 */
class OutboxPayloadTypeNotFoundException(
    val recordId: String,
    val recordKey: String,
    val payloadType: String,
    val handlerId: String,
    cause: Throwable,
) : IllegalStateException(null, cause) {
    /** Original business key, available even when the payload cannot be materialized. */
    val businessKey: String

    /** Persisted processing group, available before an outbox record is constructed. */
    val processingGroup: String

    init {
        val decodedKey = OutboxRecordKey.decode(recordKey)
        businessKey = decodedKey.businessKey
        processingGroup = decodedKey.processingGroup
    }

    override val message: String =
        "Cannot load payload type $payloadType for outbox record $recordId " +
            "(businessKey=$businessKey, processingGroup=$processingGroup, storageKey=$recordKey, handler=$handlerId)"
}
