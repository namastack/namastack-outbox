package io.namastack.outbox

/**
 * Indicates that an outbox record cannot be materialized because its payload type is unavailable.
 *
 * @param recordId ID of the affected outbox record
 * @param recordKey Key of the affected outbox record
 * @param payloadType Fully qualified name of the unavailable payload type
 * @param handlerId ID of the handler referenced by the affected record
 * @param cause Class-loading failure that prevented materialization
 * @author Roland Beisel
 * @since 1.9.0
 */
class OutboxPayloadTypeNotFoundException(
    val recordId: String,
    val recordKey: String,
    val payloadType: String,
    val handlerId: String,
    cause: ClassNotFoundException,
) : IllegalStateException(
        "Cannot find payload type $payloadType for outbox record $recordId (key=$recordKey, handler=$handlerId)",
        cause,
    )
