package io.namastack.outbox

/**
 * Indicates that an outbox record cannot be materialized because its payload type is unavailable.
 *
 * @param recordId ID of the affected outbox record
 * @param recordKey Key of the affected outbox record
 * @param payloadType Fully qualified name of the unavailable payload type
 * @param handlerId ID of the handler referenced by the affected record
 * @param context Successfully deserialized record context
 * @param cause Class-loading failure that prevented materialization
 * @author Roland Beisel
 * @since 1.10.0
 */
class OutboxPayloadTypeNotFoundException(
    val recordId: String,
    val recordKey: String,
    val payloadType: String,
    val handlerId: String,
    val context: Map<String, String> = emptyMap(),
    cause: Throwable,
) : IllegalStateException(
        "Cannot load payload type $payloadType for outbox record $recordId (key=$recordKey, handler=$handlerId)",
        cause,
    )
