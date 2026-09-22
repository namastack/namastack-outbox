package io.namastack.outbox

/**
 * Indicates that a persisted outbox record cannot be materialized because one of its serialized
 * components cannot be deserialized.
 *
 * @param recordId ID of the affected outbox record
 * @param recordKey Key of the affected outbox record
 * @param payloadType Fully qualified name of the persisted payload type
 * @param handlerId ID of the handler referenced by the affected record
 * @param target Serialized record component that could not be deserialized
 * @param context Successfully deserialized record context, or `null` when the context itself failed
 * @param cause Deserialization failure that prevented materialization
 * @author Roland Beisel
 * @since 1.10.0
 */
class OutboxRecordDeserializationException(
    val recordId: String,
    val recordKey: String,
    val payloadType: String,
    val handlerId: String,
    val target: Target,
    val context: Map<String, String>?,
    cause: Throwable,
) : IllegalStateException(
        "Cannot deserialize ${target.value} for outbox record $recordId " +
            "(key=$recordKey, payloadType=$payloadType, handler=$handlerId)",
        cause,
    ) {
    /** Serialized record component that failed to deserialize. */
    enum class Target(
        val value: String,
    ) {
        /** Persisted event payload. */
        PAYLOAD("payload"),

        /** Persisted record context. */
        CONTEXT("context"),
    }
}
